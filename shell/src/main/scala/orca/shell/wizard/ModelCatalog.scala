package orca.shell.wizard

import orca.agents.BackendTag
import orca.shell.ui.{Choice, ShellUi, UiOutcome}

/** Model-selection machinery for the wizard's per-role model step ([[Wizard]]):
  * curated aliases for claude/codex, free text for every other harness, and the
  * blank/`-` free-text convention.
  */
private[shell] object ModelCatalog:

  /** A row in a curated model picker: a specific model, "enter manually" (falls
    * through to free text), or "harness default" (no pin).
    */
  enum ModelPick:
    case Curated(model: String)
    case Manual
    case Default

  /** The flagship-first curated `(id, description)` rows for a harness. Values
    * are CLI-resolved ALIASES, not raw model ids — claude and codex resolve
    * them themselves, so this can't drift the way a curated list of raw ids
    * would (ADR 0021 §4). `Nil` means the harness is free-text only.
    */
  def curated(tag: BackendTag): List[(String, String)] =
    tag match
      case BackendTag.ClaudeCode =>
        List(
          "opus" -> "latest Opus",
          "fable" -> "latest Fable",
          "sonnet" -> "latest Sonnet"
        )
      case BackendTag.Codex =>
        List(
          "gpt-6-sol" -> "recommended",
          "gpt-6-astra" -> "most capable",
          "gpt-6-luna" -> "fast"
        )
      case _ => Nil

  /** The row a curated model picker starts on: the matching curated row if
    * `current` names one, "enter manually" if it pins something else (the
    * follow-up input is prefilled with it), else `default`'s row, else "harness
    * default".
    */
  def defaultPick(
      rows: List[(String, String)],
      current: Option[String],
      default: Option[String]
  ): ModelPick =
    val curatedIds = rows.map(_._1).toSet
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

  /** The per-harness model step: a curated select for harnesses with curated
    * aliases ([[curated]]), free text otherwise. `label` names the prompt (e.g.
    * `"Coding model"`); `current` is the existing pin, if any, offered as the
    * free-text default and as the curated select's default ([[defaultPick]]);
    * `default` is the curated model to start on when nothing is pinned.
    */
  def pick(
      ui: ShellUi,
      label: String,
      tag: BackendTag,
      default: Option[String],
      current: Option[String]
  ): UiOutcome[Option[String]] =
    val rows = curated(tag)
    if rows.isEmpty then
      val hint = freeTextHint(tag) + clearAffordance(current)
      freeText(ui, s"$label$hint", current)
    else
      val choices =
        rows.map((id, desc) => Choice(ModelPick.Curated(id), s"$id — $desc")) :+
          Choice(ModelPick.Manual, "enter manually…") :+
          Choice(ModelPick.Default, "harness default (no model pin)")
      ui.select(label, choices, Some(defaultPick(rows, current, default)))
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
