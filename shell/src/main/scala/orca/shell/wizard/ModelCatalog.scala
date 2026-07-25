package orca.shell.wizard

import orca.agents.BackendTag
import orca.shell.ui.{Choice, ShellUi, UiOutcome}

/** Model-selection machinery for the wizard's per-role model step ([[Wizard]]):
  * curated aliases for claude/codex, free text for every other harness,
  * current-pin promotion, and the blank/`-` free-text convention.
  */
private[shell] object ModelCatalog:

  /** A row in a curated model picker: a specific model, "enter manually" (falls
    * through to free text), or "harness default" (no pin).
    */
  enum ModelPick:
    case Curated(model: String)
    case Manual
    case Default

  /** The flagship-first curated `(id, description)` rows for a harness — every
    * caller's order except the wizard's Planning role, which leads with the
    * cheaper claude alias instead (`Wizard.roleDefaultOrder`). Values are
    * CLI-resolved ALIASES, not raw model ids — claude and codex resolve them
    * themselves, so this can't drift the way a curated list of raw ids would
    * (ADR 0021 §4). `Nil` means the harness is free-text only.
    */
  def defaultOrder(tag: BackendTag): List[(String, String)] =
    tag match
      case BackendTag.ClaudeCode =>
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

  /** Promotes `current`'s row to the front of `ordered` when it names one of
    * its ids (reconfigure/re-author onto an existing pin — otherwise a blind
    * Enter would silently flip it to whichever row leads instead); left as-is
    * otherwise.
    */
  def promoteCurrent(
      ordered: List[(String, String)],
      current: Option[String]
  ): List[(String, String)] =
    current.filter(id => ordered.exists(_._1 == id)) match
      case Some(id) =>
        val (front, rest) = ordered.partition(_._1 == id)
        front ++ rest
      case None => ordered

  /** Which row a curated model picker preselects: the matching curated row if
    * `current` names one, "enter manually" if it pins something else (the
    * caller prefills the follow-up input with it), else `default`'s row, else
    * "harness default".
    */
  def preselectModelPick(
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
  def freeTextHint(tag: BackendTag): String =
    tag match
      case BackendTag.Opencode =>
        " (provider/model, e.g. anthropic/claude-sonnet-5)"
      case BackendTag.Pi => " (name or pattern, `:thinking` suffix allowed)"
      case _             => ""

  /** The clear-pin affordance appended to a free-text model prompt, only when
    * there's a `current` pin to clear: with a pin prefilled as the input's
    * default, plain Enter re-submits it, so blank alone can no longer mean
    * "clear" — `-` is the explicit clear signal instead
    * ([[resolveModelInput]]).
    */
  def clearAffordance(current: Option[String]): String =
    if current.isDefined then " (Enter keeps current, - clears)" else ""

  /** A free-text model answer: blank means no pin (the common case — no
    * existing pin to keep), `-` explicitly clears an existing pin even though
    * it was prefilled as the input's default, anything else is the typed model.
    */
  def resolveModelInput(input: String): Option[String] =
    input.trim match
      case "" | "-" => None
      case model    => Some(model)

  /** The per-harness model step: a curated select for harnesses with a curated
    * order (`curated` — already role/pin-ordered by the caller, e.g. via
    * [[defaultOrder]] + [[promoteCurrent]]), free text otherwise. `label` names
    * the prompt (e.g. `"Coding model"`); `current` is the existing pin, if any,
    * offered as the free-text default and folded into the curated preselect.
    * Ordering is what actually surfaces the intended row: the interactive tty
    * backend doesn't honor `preselect` (`ConsoleUiShell.select`'s scaladoc).
    */
  def pick(
      ui: ShellUi,
      label: String,
      tag: BackendTag,
      curated: List[(String, String)],
      current: Option[String]
  ): UiOutcome[Option[String]] =
    if curated.isEmpty then
      val hint = freeTextHint(tag) + clearAffordance(current)
      freeText(ui, s"$label$hint", current)
    else
      val curatedChoices: List[Choice[ModelPick]] =
        curated.map((id, desc) => Choice(ModelPick.Curated(id), s"$id — $desc"))
      val choices =
        curatedChoices :+
          Choice(ModelPick.Manual, "enter manually…") :+
          Choice(ModelPick.Default, "harness default (no model pin)")
      // The list's own head, for when there's no current pin to promote
      // instead (preselectModelPick only consults this in that case) —
      // `curated` is only reordered away from that head when a pin was found
      // and promoted.
      val default = curated.headOption.map(_._1)
      val preselect = preselectModelPick(curated, current, default)
      ui.select(label, choices, preselect = Some(preselect))
        .flatMap:
          case ModelPick.Curated(id) => UiOutcome.Selected(Some(id))
          case ModelPick.Default     => UiOutcome.Selected(None)
          case ModelPick.Manual =>
            val hint = clearAffordance(current)
            freeText(ui, s"$label$hint", current)

  private def freeText(
      ui: ShellUi,
      prompt: String,
      current: Option[String]
  ): UiOutcome[Option[String]] =
    ui.input(prompt, default = current).map(resolveModelInput)
