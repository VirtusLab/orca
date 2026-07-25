package orca.shell.wizard

import orca.agents.BackendTag
import orca.settings.{AgentSettings, AgentSpec, SettingsFile, SettingsScope}
import orca.shell.actions.ConfigAction
import orca.shell.ui.{Choice, ShellUi, UiOutcome}
import ox.discard

/** The welcome wizard (ADR 0021 §4): detects installed harnesses, asks the user
  * to pick one per role plus its model, and writes the user-global settings
  * file. `probe` is `PathProbe.resolves(_, os.pwd)` in production, injected so
  * tests never touch a real PATH; `globalSettingsPath` is likewise injected so
  * tests never touch the developer's `~/.config`.
  */
private[shell] class Wizard(
    ui: ShellUi,
    probe: String => Boolean,
    globalSettingsPath: os.Path
):

  /** Runs the three role prompts (planning, coding, review, in that order) and
    * writes the result. Each role prompt asks for a harness, then a model for
    * that harness (Step 2, ADR 0021 §4). `reconfigure = true` pre-selects each
    * role's current harness (falling back to the first detected harness, else
    * `claude`, exactly as on first run) and its current model pin. Returns
    * `false` without writing anything as soon as any prompt is
    * [[UiOutcome.Cancelled]].
    */
  def run(reconfigure: Boolean): Boolean =
    val existingContent =
      Option.when(os.exists(globalSettingsPath))(os.read(globalSettingsPath))
    val current =
      if reconfigure then
        existingContent
          .flatMap(content =>
            SettingsFile.parse(content, SettingsScope.UserGlobal).toOption
          )
          .map(_.agents)
          .getOrElse(AgentSettings.empty)
      else AgentSettings.empty

    val detected = BackendTag.values
      .filter(tag => probe(AgentSpec.harnessNameFor(tag)))
      .toSet
    val fallback =
      BackendTag.values.find(detected.contains).getOrElse(BackendTag.ClaudeCode)

    val chosen =
      for
        planning <- selectRole(
          Wizard.Role.Planning,
          current.planning,
          detected,
          fallback
        )
        coding <- selectRole(
          Wizard.Role.Coding,
          current.coding,
          detected,
          fallback
        )
        review <- selectRole(
          Wizard.Role.Review,
          current.review,
          detected,
          fallback
        )
      yield AgentSettings(Some(planning), Some(coding), Some(review))

    chosen match
      case UiOutcome.Cancelled => false
      case UiOutcome.Selected(agents) =>
        ConfigAction.set(globalSettingsPath, agents)
        true

  /** Offers to rewrite a malformed global settings file from scratch via the
    * wizard (ADR 0021 §4). Declining leaves the file untouched and skips the
    * wizard, so every flow run keeps failing loudly on it until it's fixed by
    * hand or via Re-configure. Accepting does NOT remove the file up front —
    * [[ConfigAction.set]]'s malformed-content check already rewrites it
    * wholesale, so removing it early would only lose the original content for
    * nothing if the user then cancels mid-wizard. The caller (`Main`) is
    * responsible for surfacing the parse error itself; this only handles the
    * confirm-and-rewrite action.
    */
  private[shell] def repairMalformed(): Unit =
    ui.confirm(
      "Rewrite it from scratch with the wizard?",
      default = false
    ) match
      case UiOutcome.Selected(true) => run(reconfigure = false).discard
      case _                        => ()

  private def selectRole(
      role: Wizard.Role,
      current: Option[AgentSpec],
      detected: Set[BackendTag],
      fallback: BackendTag
  ): UiOutcome[AgentSpec] =
    val currentTag = current.map(_.backend)
    val choices =
      BackendTag.values.toList.map(tag => choiceFor(tag, currentTag, detected))
    for
      tag <- ui.select(
        s"${role.label} agent",
        choices,
        preselect = currentTag.orElse(Some(fallback))
      )
      currentModel =
        if currentTag.contains(tag) then current.flatMap(_.model) else None
      model <- selectModel(role, tag, currentModel)
    yield AgentSpec(tag, model)

  /** The per-harness model step: a curated select for harnesses with
    * CLI-resolved aliases ([[Wizard.curatedModels]]), free text otherwise.
    * `currentModel` is the role's existing pin, already dropped by the caller
    * if the harness changed.
    */
  private def selectModel(
      role: Wizard.Role,
      tag: BackendTag,
      currentModel: Option[String]
  ): UiOutcome[Option[String]] =
    val curated = Wizard.curatedModels(role, tag, currentModel)
    if curated.isEmpty then
      val hint = Wizard.freeTextHint(tag) + Wizard.clearAffordance(currentModel)
      freeTextModel(s"${role.label} model$hint", currentModel)
    else
      val curatedChoices: List[Choice[Wizard.ModelPick]] =
        curated.map((id, desc) =>
          Choice(Wizard.ModelPick.Curated(id), s"$id — $desc")
        )
      val choices =
        curatedChoices :+
          Choice(Wizard.ModelPick.Manual, "enter manually…") :+
          Choice(Wizard.ModelPick.Default, "harness default (no model pin)")
      // The role default, for when there's no current pin to promote instead
      // (preselectModelPick only consults this in that case) — `curated`'s own
      // head, since it's only reordered away from role-default-first when a
      // pin was found and promoted.
      val default = curated.headOption.map(_._1)
      val preselect =
        Wizard.preselectModelPick(curated, currentModel, default)
      ui.select(s"${role.label} model", choices, preselect = Some(preselect))
        .flatMap:
          case Wizard.ModelPick.Curated(id) => UiOutcome.Selected(Some(id))
          case Wizard.ModelPick.Default     => UiOutcome.Selected(None)
          case Wizard.ModelPick.Manual =>
            val hint = Wizard.clearAffordance(currentModel)
            freeTextModel(s"${role.label} model$hint", currentModel)

  /** A single free-text model prompt: Enter keeps `currentModel` (the prompt's
    * default), `-` clears it, anything else is the typed model
    * ([[Wizard.resolveModelInput]]).
    */
  private def freeTextModel(
      prompt: String,
      currentModel: Option[String]
  ): UiOutcome[Option[String]] =
    ui.input(prompt, default = currentModel).map(Wizard.resolveModelInput)

  private def choiceFor(
      tag: BackendTag,
      current: Option[BackendTag],
      detected: Set[BackendTag]
  ): Choice[BackendTag] =
    val name = AgentSpec.harnessNameFor(tag)
    val marked = if current.contains(tag) then s"$name (current)" else name
    Choice(tag, s"$marked — ${Wizard.pathStatus(detected(tag))}")

private[shell] object Wizard:

  /** The `✓ found` / `not found on PATH` detection suffix shared by every
    * harness picker's row label ([[Wizard.choiceFor]] and `Main.harnessLabel`).
    */
  def pathStatus(found: Boolean): String =
    if found then "✓ found" else "not found on PATH"

  private[wizard] enum Role(val label: String):
    case Planning extends Role("Planning")
    case Coding extends Role("Coding")
    case Review extends Role("Review")

  /** A row in a curated model picker: a specific model, "enter manually" (falls
    * through to free text), or "harness default" (no pin).
    */
  private[wizard] enum ModelPick:
    case Curated(model: String)
    case Manual
    case Default

  /** Curated `(id, description)` rows for a role's harness: the matching row
    * for `current` first when it names one of these ids (reconfigure onto an
    * existing pin — otherwise a blind Enter would silently flip it to whichever
    * row `roleDefaultOrder` puts first instead), else the role's default row
    * first as `roleDefaultOrder` orders them. Either way, ordering is what
    * actually surfaces the intended row: the interactive tty backend doesn't
    * honor `preselect` (`ConsoleUiShell.select`'s scaladoc). `Nil` means the
    * harness is free-text only.
    */
  private[wizard] def curatedModels(
      role: Role,
      tag: BackendTag,
      current: Option[String] = None
  ): List[(String, String)] =
    val ordered = roleDefaultOrder(role, tag)
    current.filter(id => ordered.exists(_._1 == id)) match
      case Some(id) =>
        val (front, rest) = ordered.partition(_._1 == id)
        front ++ rest
      case None => ordered

  /** The base curated order, role default first, before any current-pin
    * promotion: Planning gets the cheaper claude alias, Coding/Review get the
    * flagship; codex's flagship alias leads for every role. Values are
    * CLI-resolved ALIASES, not raw model ids — claude and codex resolve them
    * themselves, so the list can't drift the way a curated list of raw ids
    * would (ADR 0021 §4).
    */
  private def roleDefaultOrder(
      role: Role,
      tag: BackendTag
  ): List[(String, String)] =
    tag match
      case BackendTag.ClaudeCode =>
        if role == Role.Planning then
          List(
            "fable" -> "Fable 5",
            "opus" -> "latest Opus",
            "sonnet" -> "latest Sonnet"
          )
        else
          List(
            "opus" -> "latest Opus",
            "fable" -> "Fable 5",
            "sonnet" -> "latest Sonnet"
          )
      case BackendTag.Codex =>
        List(
          "gpt-5.6-sol" -> "flagship",
          "gpt-5.6-terra" -> "balanced",
          "gpt-5.6-luna" -> "fast"
        )
      case _ => Nil

  /** Which row a curated model picker preselects: the matching curated row if
    * `current` names one, "enter manually" if it pins something else (the
    * caller prefills the follow-up input with it), else the role's default row,
    * else "harness default".
    */
  private[wizard] def preselectModelPick(
      curated: List[(String, String)],
      current: Option[String],
      default: Option[String]
  ): ModelPick =
    val curatedIds = curated.map(_._1).toSet
    current match
      case Some(model) if curatedIds.contains(model) => ModelPick.Curated(model)
      case Some(_)                                   => ModelPick.Manual
      case None =>
        default.map(ModelPick.Curated.apply).getOrElse(ModelPick.Default)

  /** The free-text hint appended to the model prompt for harnesses picked
    * entirely by hand.
    */
  private[wizard] def freeTextHint(tag: BackendTag): String =
    tag match
      case BackendTag.Opencode =>
        " (provider/model, e.g. anthropic/claude-sonnet-5)"
      case BackendTag.Pi => " (name or pattern, `:thinking` suffix allowed)"
      case _             => ""

  /** The clear-pin affordance appended to a free-text model prompt, only when
    * there's a `current` pin to clear: with a pin prefilled as the input's
    * default, plain Enter re-submits it (`NumberedUi.input`), so blank alone
    * can no longer mean "clear" — `-` is the explicit clear signal instead
    * ([[resolveModelInput]]).
    */
  private[wizard] def clearAffordance(current: Option[String]): String =
    if current.isDefined then " (Enter keeps current, - clears)" else ""

  /** A free-text model answer: blank means no pin (the common case — no
    * existing pin to keep), `-` explicitly clears an existing pin even though
    * it was prefilled as the input's default, anything else is the typed model.
    */
  private[wizard] def resolveModelInput(input: String): Option[String] =
    input.trim match
      case "" | "-" => None
      case model    => Some(model)
