package orca.shell.wizard

import orca.agents.BackendTag
import orca.settings.{AgentSettings, AgentSpec, SettingsFile, SettingsScope}
import orca.shell.actions.ConfigAction
import orca.shell.ui.{Choice, ShellUi, UiOutcome}
import ox.discard

/** The welcome wizard (ADR 0021 §4): detects installed harnesses, asks the user
  * to pick one per role, and writes the user-global settings file. `probe` is
  * `PathProbe.resolves(_, os.pwd)` in production, injected so tests never touch
  * a real PATH; `globalSettingsPath` is likewise injected so tests never touch
  * the developer's `~/.config`.
  */
class Wizard(
    ui: ShellUi,
    probe: String => Boolean,
    globalSettingsPath: os.Path
):

  /** Runs the three role prompts (planning, coding, review, in that order) and
    * writes the result. `reconfigure = true` pre-selects each role's current
    * harness (falling back to the first detected harness, else `claude`,
    * exactly as on first run) and keeps that role's existing model pin when its
    * harness is unchanged. Returns `false` without writing anything as soon as
    * any prompt is [[UiOutcome.Cancelled]].
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
        planning <- selectRole("Planning", current.planning, detected, fallback)
        coding <- selectRole("Coding", current.coding, detected, fallback)
        review <- selectRole("Review", current.review, detected, fallback)
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
      roleLabel: String,
      current: Option[AgentSpec],
      detected: Set[BackendTag],
      fallback: BackendTag
  ): UiOutcome[AgentSpec] =
    val currentTag = current.map(_.backend)
    val choices =
      BackendTag.values.toList.map(tag => choiceFor(tag, currentTag, detected))
    ui.select(
      s"$roleLabel agent",
      choices,
      preselect = currentTag.orElse(Some(fallback))
    ).map(tag => Wizard.resolvedSpec(tag, currentTag, current))

  private def choiceFor(
      tag: BackendTag,
      current: Option[BackendTag],
      detected: Set[BackendTag]
  ): Choice[BackendTag] =
    val name = AgentSpec.harnessNameFor(tag)
    val marked = if current.contains(tag) then s"$name (current)" else name
    val status = if detected(tag) then "✓ found" else "not found on PATH"
    Choice(tag, s"$marked — $status")

object Wizard:

  /** The model-pin retention rule (ADR 0021 §4): a pin only means something for
    * the harness it was pinned under, so keep it when `tag` matches the role's
    * current harness and drop it the moment the user picks any other.
    */
  private[wizard] def resolvedSpec(
      tag: BackendTag,
      currentTag: Option[BackendTag],
      current: Option[AgentSpec]
  ): AgentSpec =
    val model =
      if currentTag.contains(tag) then current.flatMap(_.model) else None
    AgentSpec(tag, model)
