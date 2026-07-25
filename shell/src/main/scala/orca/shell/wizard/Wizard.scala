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

  /** The per-harness model step ([[ModelCatalog.pick]], shared with the
    * authoring harness picker): a curated select for harnesses with
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
    ModelCatalog.pick(ui, s"${role.label} model", tag, curated, currentModel)

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

  /** Re-exported from [[ModelCatalog]] (also consumed by the authoring harness
    * picker, `Main.selectAuthoringModel`) so existing `Wizard.ModelPick`/etc.
    * call sites — including tests — need no change.
    */
  private[wizard] type ModelPick = ModelCatalog.ModelPick
  private[wizard] val ModelPick: ModelCatalog.ModelPick.type =
    ModelCatalog.ModelPick

  private[wizard] def preselectModelPick(
      curated: List[(String, String)],
      current: Option[String],
      default: Option[String]
  ): ModelPick = ModelCatalog.preselectModelPick(curated, current, default)

  private[wizard] def freeTextHint(tag: BackendTag): String =
    ModelCatalog.freeTextHint(tag)

  private[wizard] def clearAffordance(current: Option[String]): String =
    ModelCatalog.clearAffordance(current)

  private[wizard] def resolveModelInput(input: String): Option[String] =
    ModelCatalog.resolveModelInput(input)

  /** Curated `(id, description)` rows for a role's harness:
    * [[roleDefaultOrder]] with `current`'s row promoted to the front when it
    * names one of these ids ([[ModelCatalog.promoteCurrent]]) — reconfigure
    * onto an existing pin, otherwise a blind Enter would silently flip it to
    * whichever row `roleDefaultOrder` puts first instead. `Nil` means the
    * harness is free-text only.
    */
  private[wizard] def curatedModels(
      role: Role,
      tag: BackendTag,
      current: Option[String] = None
  ): List[(String, String)] =
    ModelCatalog.promoteCurrent(roleDefaultOrder(role, tag), current)

  /** The base curated order, role default first, before any current-pin
    * promotion: Planning gets the cheaper claude alias, everyone else
    * ([[ModelCatalog.defaultOrder]]) gets the flagship.
    */
  private def roleDefaultOrder(
      role: Role,
      tag: BackendTag
  ): List[(String, String)] =
    (role, tag) match
      case (Role.Planning, BackendTag.ClaudeCode) =>
        List(
          "fable" -> "Fable 5",
          "opus" -> "latest Opus",
          "sonnet" -> "latest Sonnet"
        )
      case _ => ModelCatalog.defaultOrder(tag)
