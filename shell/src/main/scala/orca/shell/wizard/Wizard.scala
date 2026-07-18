package orca.shell.wizard

import orca.agents.BackendTag
import orca.settings.{AgentSettings, AgentSpec, SettingsFile, SettingsScope}
import orca.shell.ui.{Choice, ShellUi, UiOutcome}

/** The welcome wizard (ADR 0021 §4): detects installed harnesses, asks the
  * user to pick one per role, and writes the user-global settings file.
  * `probe` is `PathProbe.resolves(_, os.pwd)` in production, injected so
  * tests never touch a real PATH; `globalSettingsPath` is likewise injected
  * so tests never touch the developer's `~/.config`.
  */
class Wizard(ui: ShellUi, probe: String => Boolean, globalSettingsPath: os.Path):

  /** Runs the three role prompts (planning, coding, review, in that order)
    * and writes the result. `reconfigure = true` pre-selects each role's
    * current harness (falling back to the first detected harness, else
    * `claude`, exactly as on first run) and keeps that role's existing model
    * pin when its harness is unchanged. Returns `false` without writing
    * anything as soon as any prompt is [[UiOutcome.Cancelled]].
    */
  def run(reconfigure: Boolean): Boolean =
    val exists = os.exists(globalSettingsPath)
    val currentContent = if exists then os.read(globalSettingsPath) else ""
    // A malformed existing file (only reachable if the caller re-configures
    // past a parse error without offering the repair-rewrite) still lets the
    // wizard run: fall back to no pre-selection rather than fail the wizard.
    val current =
      if reconfigure then
        SettingsFile.parse(currentContent, SettingsScope.UserGlobal).map(_.agents).getOrElse(AgentSettings.empty)
      else AgentSettings.empty

    val detected = BackendTag.values.filter(tag => probe(AgentSpec.harnessNameFor(tag))).toSet
    val fallback = BackendTag.values.find(detected.contains).getOrElse(BackendTag.ClaudeCode)

    selectRole("Planning", current.planning, detected, fallback) match
      case UiOutcome.Cancelled => false
      case UiOutcome.Selected(planning) =>
        selectRole("Coding", current.coding, detected, fallback) match
          case UiOutcome.Cancelled => false
          case UiOutcome.Selected(coding) =>
            selectRole("Review", current.review, detected, fallback) match
              case UiOutcome.Cancelled => false
              case UiOutcome.Selected(review) =>
                write(exists, currentContent, AgentSettings(Some(planning), Some(coding), Some(review)))
                true

  private def selectRole(
      roleLabel: String,
      current: Option[AgentSpec],
      detected: Set[BackendTag],
      fallback: BackendTag
  ): UiOutcome[AgentSpec] =
    val currentTag = current.map(_.backend)
    val choices = BackendTag.values.toList.map(tag => choiceFor(tag, currentTag, detected))
    ui.select(s"$roleLabel agent", choices, preselect = currentTag.orElse(Some(fallback))) match
      case UiOutcome.Cancelled => UiOutcome.Cancelled
      case UiOutcome.Selected(tag) =>
        // A model pin only means something for the harness it was pinned
        // under (ADR 0021 §4): keep it when the user re-picks that harness,
        // drop it the moment they switch.
        val model = if currentTag.contains(tag) then current.flatMap(_.model) else None
        UiOutcome.Selected(AgentSpec(tag, model))

  private def choiceFor(tag: BackendTag, current: Option[BackendTag], detected: Set[BackendTag]): Choice[BackendTag] =
    val name = AgentSpec.harnessNameFor(tag)
    val marked = if current.contains(tag) then s"$name (current)" else name
    val status = if detected(tag) then "✓ found" else "not found on PATH"
    Choice(tag, s"$marked — $status")

  private def write(exists: Boolean, currentContent: String, agents: AgentSettings): Unit =
    val content =
      if exists then SettingsFile.updateGlobal(currentContent, agents) else SettingsFile.renderGlobal(agents)
    os.write.over(globalSettingsPath, content, createFolders = true)
    println(
      s"Settings written to $globalSettingsPath — hand-editable any time " +
        "(`harness[:model]`, e.g. `claude:sonnet`)."
    )
    println(
      "Note: changing a role's harness makes previously recorded sessions for " +
        "that role mint fresh, with a warning, the next time they'd resume (ADR 0020 §8)."
    )
