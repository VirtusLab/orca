package orca.shell

import org.jline.terminal.{Terminal, TerminalBuilder}
import orca.StackSettings
import orca.agents.BackendTag
import orca.runner.ManifestSession
import orca.settings.{AgentSpec, GlobalSettings, SettingsFile, SettingsScope}
import orca.shell.actions.{
  AuthorAction,
  AuthorParams,
  EditAction,
  FlowResolution,
  RunAction,
  SessionAction,
  SessionSelection,
  StackAction,
  StackStatus,
  ViewAction
}
import orca.shell.create.{CreateFlow, CreateTarget, CreateTier}
import orca.shell.flows.{CustomizeTier, DiscoveredFlow, FlowOrigin}
import orca.shell.run.FallbackPolicy
import orca.shell.sessions.{ManifestReader, ReadRun, ResumeCommand}
import orca.shell.ui.{Choice, ShellOutput, ShellUi, UiOutcome}
import orca.shell.wizard.{FirstRun, FirstRunStatus, Wizard}
import orca.subprocess.PathProbe
import ox.discard

import java.time.Instant
import scala.annotation.tailrec
import scala.util.Try

/** Entry point for the `orca` shell executable (ADR 0021). */
object Main:

  def main(args: Array[String]): Unit =
    // scala-cli's dependency-download progress writes bare `\r` (no line
    // clear) before handing control here, so the cursor can still be mid-line
    // over that stale text; clear it first so the banner starts on a clean
    // line instead of appending to it.
    print("[2K\r")
    ShellOutput.info(s"orca shell ${ShellVersion.value}")
    val terminal = TerminalBuilder.builder().system(true).dumb(true).build()
    try
      val ui = ShellUi.make(terminal)
      val globalSettingsPath = GlobalSettings.default
      val wizard = Wizard(ui, PathProbe.resolves(_, os.pwd), globalSettingsPath)
      runWizardIfFirstRun(wizard, globalSettingsPath)
      loop(ui, wizard, terminal, ShellUi.isInteractive(terminal))
    finally terminal.close()

  /** Runs the welcome wizard before the first menu when [[FirstRun.check]]
    * reports [[FirstRunStatus.FirstRun]] (ADR 0021 §4). A malformed global file
    * is NOT first-run: its parse error is surfaced here, and the
    * confirm-and-rewrite offer itself is [[Wizard.repairMalformed]].
    */
  private def runWizardIfFirstRun(
      wizard: Wizard,
      globalSettingsPath: os.Path
  ): Unit =
    FirstRun.check(globalSettingsPath) match
      case Right(FirstRunStatus.FirstRun) =>
        wizard.run(reconfigure = false).discard
      case Right(FirstRunStatus.AlreadyConfigured) => ()
      case Left(error) =>
        ShellOutput.error(
          s"the global settings file is malformed — ${error.message}"
        )
        wizard.repairMalformed()

  /** Runs the main menu until Exit is chosen or the top-level prompt is
    * cancelled (Ctrl-C / EOF). Continue a session re-reads `.orca/cache/runs/`
    * on every redraw (ADR 0021 §8) — a flow run started from this same menu can
    * only have just finished, so the freshest listing is worth the re-read.
    */
  @tailrec private def loop(
      ui: ShellUi,
      wizard: Wizard,
      terminal: Terminal,
      tty: Boolean
  ): Unit =
    val (runs, warnings) = ManifestReader.list(os.pwd, pidAlive)
    warnings.foreach(ShellOutput.info)
    val continueDisabledReason =
      if runs.nonEmpty then None else Some("no sessions recorded yet")
    val newestRunSessionCount =
      runs.headOption.fold(0)(_.manifest.sessions.size)
    ui.select(
      "orca shell",
      MainMenu.choices(continueDisabledReason, newestRunSessionCount)
    ) match
      case UiOutcome.Cancelled               => ()
      case UiOutcome.Selected(MenuItem.Exit) => ()
      case UiOutcome.Selected(MenuItem.Reconfigure) =>
        wizard.run(reconfigure = true).discard
        loop(ui, wizard, terminal, tty)
      case UiOutcome.Selected(MenuItem.RediscoverStack) =>
        rediscoverStack(ui, os.pwd)
        loop(ui, wizard, terminal, tty)
      case UiOutcome.Selected(MenuItem.ViewFlow) =>
        viewFlow(ui, tty)
        loop(ui, wizard, terminal, tty)
      case UiOutcome.Selected(MenuItem.EditFlow) =>
        editFlow(ui, terminal)
        loop(ui, wizard, terminal, tty)
      case UiOutcome.Selected(MenuItem.RunFlow) =>
        runFlow(ui, terminal)
        loop(ui, wizard, terminal, tty)
      case UiOutcome.Selected(MenuItem.CreateFlow) =>
        createNewFlow(ui, terminal)
        loop(ui, wizard, terminal, tty)
      case UiOutcome.Selected(MenuItem.ForkFlow) =>
        createForkFlow(ui, terminal)
        loop(ui, wizard, terminal, tty)
      case UiOutcome.Selected(MenuItem.ContinueSession) =>
        continueSession(ui, terminal, runs)
        loop(ui, wizard, terminal, tty)

  /** `ProcessHandle.of` finds nothing for a pid that's been reaped — treated as
    * not alive, same as a live handle reporting `isAlive == false`.
    */
  private def pidAlive(pid: Long): Boolean =
    ProcessHandle.of(pid).map[Boolean](_.isAlive).orElse(false)

  /** Prints the chosen flow's source (highlighted when `tty`) and returns — the
    * menu redraws on the next loop iteration, so no pager is needed (ADR 0021
    * §6).
    */
  private def viewFlow(ui: ShellUi, tty: Boolean): Unit =
    selectFlow(ui, "View which flow?").foreach: flow =>
      println(ViewAction.render(flow, tty))

  /** Opens the chosen flow in `$VISUAL`/`$EDITOR`/`vi`. Project and global
    * flows are edited in place ([[EditAction.editInPlace]]); a built-in is
    * never edited in its cache copy, so [[promptCustomizeTier]] plus
    * [[EditAction.customizeThenEdit]] copy it into a tier first.
    */
  private def editFlow(ui: ShellUi, terminal: Terminal): Unit =
    selectFlow(ui, "Edit which flow?").foreach: flow =>
      if flow.origin != FlowOrigin.BuiltIn then
        EditAction.editInPlace(terminal, flow.path).discard
      else
        promptCustomizeTier(ui, flow).foreach: tier =>
          EditAction.customizeThenEdit(
            terminal,
            flow,
            tier,
            os.pwd,
            GlobalSettings.defaultFlows
          ) match
            case Left(message) => ShellOutput.error(message)
            case Right(_)      => ()

  /** Selects a flow, prompts for the task text, then hands off to
    * [[RunAction.run]]. Verbose is not exposed here in v1 — task text only; a
    * later task can add a verbose confirm alongside session tracking.
    */
  private def runFlow(ui: ShellUi, terminal: Terminal): Unit =
    for
      flow <- selectFlow(ui, "Run which flow?")
      task <- promptTask(ui)
    do
      val opts = RunAction.RunOptions(
        verbose = false,
        fallback = FallbackPolicy.Ask(ui)
      )
      RunAction.run(flow, task, opts, os.pwd, terminal).discard

  /** Prompts for the flow's task text, re-prompting on blank input — an empty
    * `userPrompt` reaches the flow's agent directly (branch naming, the coding
    * session's instructions), so it's rejected here rather than passed through
    * as a degenerate run.
    */
  @tailrec private def promptTask(ui: ShellUi): Option[String] =
    ui.inputMultiline("Task for the flow") match
      case UiOutcome.Cancelled => None
      case UiOutcome.Selected(text) if text.trim.isEmpty =>
        ShellOutput.error("task text can't be empty")
        promptTask(ui)
      case UiOutcome.Selected(text) => Some(text)

  /** New-flow authoring (item 9): tier → goal → filename (defaulted from the
    * goal's [[suggestedFilename]] slug) → harness+yolo, then hands off to
    * [[AuthorAction.create]]. Cancelling any prompt, or a filename collision,
    * aborts back to the menu without launching anything. Reachable via
    * [[MenuItem.CreateFlow]]; [[MenuItem.ForkFlow]] (item 10) is the sibling
    * entry point for [[createForkFlow]].
    */
  private def createNewFlow(ui: ShellUi, terminal: Terminal): Unit =
    val workDir = os.pwd
    val globalFlows = GlobalSettings.defaultFlows
    for
      tier <- selectCreateTier(
        ui,
        "Where should the new flow be saved:",
        globalFlows
      )
      goal <- promptDescription(ui, "Describe what the flow should do")
      target <- promptFlowTarget(
        ui,
        tier,
        workDir,
        globalFlows,
        default = Some(suggestedFilename(goal))
      )
      (backend, yolo) <- selectHarnessAndYolo(ui)
    do
      val params = AuthorParams(tier, target, backend, yolo)
      AuthorAction.create(goal, params, workDir, ui, terminal).discard

  /** Fork-an-existing-flow authoring (item 10): pick the source flow from every
    * tier (same rows View/Edit use) → describe the changes → tier for the
    * fork's target → filename (defaulted from
    * [[CreateFlow.forkFilenameDefault]]) → harness+yolo, then hands off to
    * [[AuthorAction.fork]].
    */
  private def createForkFlow(ui: ShellUi, terminal: Terminal): Unit =
    val workDir = os.pwd
    val globalFlows = GlobalSettings.defaultFlows
    for
      source <- selectFlow(ui, "Fork which flow:")
      changes <- promptDescription(ui, "Describe the changes for the fork")
      tier <- selectCreateTier(
        ui,
        "Where should the fork be saved:",
        globalFlows
      )
      target <- promptFlowTarget(
        ui,
        tier,
        workDir,
        globalFlows,
        default = Some(CreateFlow.forkFilenameDefault(source.name))
      )
      (backend, yolo) <- selectHarnessAndYolo(ui)
    do
      val params = AuthorParams(tier, target, backend, yolo)
      AuthorAction.fork(source, changes, params, workDir, ui, terminal).discard

  private def selectCreateTier(
      ui: ShellUi,
      title: String,
      globalFlows: os.Path
  ): Option[CreateTier] =
    ui.select(
      title,
      List(
        Choice(CreateTier.Project, "Project (.orca/flows/)"),
        Choice(CreateTier.Global, s"Global ($globalFlows)")
      )
    ) match
      case UiOutcome.Cancelled      => None
      case UiOutcome.Selected(tier) => Some(tier)

  /** The new flow's filename suggestion (item 9's cheap slug prompt): runs the
    * configured coding agent — not the harness picked later in this same flow
    * ([[selectHarnessAndYolo]] hasn't been asked yet at this point) —
    * non-interactively via [[CreateFlow.suggestFilename]], falling back to its
    * own local word-based derivation within a few seconds if that harness is
    * slow, absent, or unreachable.
    */
  private def suggestedFilename(goal: String): String =
    CreateFlow.suggestFilename(
      configuredCodingAgent(GlobalSettings.default),
      goal
    )

  /** Prompts for the flow's filename (pre-filled with `default`, e.g. the
    * goal's suggested slug or the fork's `-fork.sc` suggestion — either way
    * editable, per `ui.input`'s default-hint path) and resolves it to a target
    * path via [[CreateFlow.prepareTarget]], re-prompting with the same
    * `default` on a collision (printing the reason first) rather than aborting
    * the whole create-flow attempt over one taken name — the harness writes the
    * flow file itself, so an existing file at the target path is never
    * overwritten.
    */
  @tailrec private def promptFlowTarget(
      ui: ShellUi,
      tier: CreateTier,
      workDir: os.Path,
      globalFlows: os.Path,
      default: Option[String]
  ): Option[CreateTarget] =
    ui.input("Flow filename:", default) match
      case UiOutcome.Cancelled => None
      case UiOutcome.Selected(rawName) =>
        CreateFlow.prepareTarget(tier, rawName, workDir, globalFlows) match
          case Left(message) =>
            ShellOutput.error(message)
            promptFlowTarget(ui, tier, workDir, globalFlows, default)
          case Right(target) => Some(target)

  /** Prompts for a multi-line description (the new flow's goal, or the fork's
    * described changes), re-prompting on blank input — mirrors [[promptTask]]'s
    * rationale: an empty description would reach the harness as a degenerate
    * initial prompt.
    */
  @tailrec private def promptDescription(
      ui: ShellUi,
      label: String
  ): Option[String] =
    ui.inputMultiline(label) match
      case UiOutcome.Cancelled => None
      case UiOutcome.Selected(text) if text.trim.isEmpty =>
        ShellOutput.error("description can't be empty")
        promptDescription(ui, label)
      case UiOutcome.Selected(text) => Some(text)

  /** Harness picker (see [[selectHarness]]) plus the yolo confirm (item 11):
    * `"Run the harness without approval prompts (yolo)?"`, defaulting to yes to
    * match that Orca's own flows already run autonomously by default.
    */
  private def selectHarnessAndYolo(ui: ShellUi): Option[(BackendTag, Boolean)] =
    for
      backend <- selectHarness(ui)
      yolo <- promptYolo(ui)
    yield (backend, yolo)

  private def promptYolo(ui: ShellUi): Option[Boolean] =
    ui.confirm(
      "Run the harness without approval prompts (yolo)?",
      default = true
    ) match
      case UiOutcome.Cancelled   => None
      case UiOutcome.Selected(v) => Some(v)

  /** Harness picker, preselecting the configured coding agent (falling back to
    * claude when the global settings file is absent or unparseable — same
    * fallback [[Wizard]] uses for an undetected default). Labels get the same
    * PATH-detection suffix as the wizard's harness picker
    * ([[Wizard.choiceFor]]) — every harness stays selectable regardless of
    * detection, since this is one-off informational decoration, not a gate.
    */
  private def selectHarness(ui: ShellUi): Option[BackendTag] =
    val default = configuredCodingAgent(GlobalSettings.default)
    ui.select(
      "Harness for the authoring session:",
      BackendTag.values.toList.map(tag =>
        Choice(tag, harnessLabel(tag, PathProbe.resolves(_, os.pwd)))
      ),
      preselect = Some(default)
    ) match
      case UiOutcome.Cancelled         => None
      case UiOutcome.Selected(backend) => Some(backend)

  /** `<name> — ✓ found` / `<name> — not found on PATH`, matching
    * [[Wizard.choiceFor]]'s status suffix. `probe` is injected so tests never
    * touch a real PATH.
    */
  private[shell] def harnessLabel(
      tag: BackendTag,
      probe: String => Boolean
  ): String =
    val name = AgentSpec.harnessNameFor(tag)
    val status = if probe(name) then "✓ found" else "not found on PATH"
    s"$name — $status"

  private def configuredCodingAgent(globalSettingsPath: os.Path): BackendTag =
    Option
      .when(os.exists(globalSettingsPath))(os.read(globalSettingsPath))
      .flatMap(content =>
        SettingsFile.parse(content, SettingsScope.UserGlobal).toOption
      )
      .flatMap(_.agents.coding)
      .map(_.backend)
      .getOrElse(BackendTag.ClaudeCode)

  /** One session occurrence paired with the run it came from — the unit
    * [[sessionRows]] groups, sorts, and labels. Carrying the whole [[ReadRun]]
    * (not just its `crashed` flag) keeps [[SessionSelection]] constructible
    * straight from an occurrence.
    */
  private case class Occurrence(run: ReadRun, session: ManifestSession)

  /** One outcome of the continue-session picker: either resume a specific
    * session, or re-render the picker with the collapsed groups (older lineage
    * occurrences, one-shots) expanded.
    */
  private[shell] enum PickerRow:
    case Resume(selection: SessionSelection)
    case ShowMore

  /** Prompts among every session across `runs` and resumes the chosen one.
    * Picking the expander re-renders the same picker with `expanded = true`;
    * there is no way back to the collapsed view short of re-opening the menu
    * item, which is fine — the picker is re-read from disk on every open
    * anyway. A cancelled prompt, or `runs` being empty (unreachable via the
    * menu today, since the item is disabled then, but harmless), is a silent
    * no-op.
    */
  private def continueSession(
      ui: ShellUi,
      terminal: Terminal,
      runs: List[ReadRun],
      expanded: Boolean = false
  ): Unit =
    ui.select("Continue which session?", sessionRows(runs, expanded)) match
      case UiOutcome.Cancelled => ()
      case UiOutcome.Selected(PickerRow.ShowMore) =>
        continueSession(ui, terminal, runs, expanded = true)
      case UiOutcome.Selected(PickerRow.Resume(selection)) =>
        SessionAction.resume(terminal, selection) match
          case Left(message) => ShellOutput.error(message)
          case Right(_)      => ()

  /** Builds the continue-session picker's rows (ADR 0021 §8, research 08 items
    * 7+8): durable lineages first, one-shots last, with two kinds of rows
    * collapsed by default behind an expander.
    *
    * A durable lineage is a `(agent, sessionName)` pair with `kind ==
    * "durable"` — every occurrence of it across every run in `runs`, not just
    * the newest run, since a lineage's `sessionName` is stable across separate
    * flow runs (a fresh run mints a fresh `clientId`/`wireId` but reuses the
    * same `agent.session(name, ...)` name) while a single run's own durable
    * session always upserts onto one manifest row. Only the occurrence with the
    * max `lastActiveAt` is shown (marked `★ ... — latest`, the primary
    * continuation target); the rest collapse behind a "show N earlier
    * occurrences" row. One-shot sessions (`kind == "oneShot"` — Plan-stage
    * calls, reviewer-selection calls, reviewer `chat()` runs) are never deduped
    * — each is a genuinely distinct fresh session — but collapse behind a
    * single "show N one-shot sessions" row, since these are the rows that
    * otherwise flood the picker with same-named, low-value entries.
    *
    * `expanded` reveals both collapsed groups in place, sorted the same as the
    * primary rows (newest `lastActiveAt` first). Disabling a row previews only
    * what [[ResumeCommand.staticGate]] can tell without a live harness call: a
    * wireId-less session (pi always) or an unrecognised harness. Gemini's real
    * resumability needs `gemini --list-sessions` (deferred to selection, in
    * [[SessionAction.resume]]) — `staticGate` passes any gemini session with a
    * wireId, leaving its row enabled pending that later check.
    */
  private[shell] def sessionRows(
      runs: List[ReadRun],
      expanded: Boolean
  ): List[Choice[PickerRow]] =
    val occurrences =
      for
        run <- runs
        session <- run.manifest.sessions
      yield Occurrence(run, session)
    val (durable, oneShot) = occurrences.partition(_.session.kind == "durable")

    val lineages = durable
      .groupBy(o => (o.session.agent, o.session.sessionName))
      .values
      .map(_.sortBy(recency).reverse)
      .toList
    val primary = lineages.map(_.head).sortBy(recency).reverse
    val earlier = lineages.flatMap(_.tail).sortBy(recency).reverse
    val oneShotSorted = oneShot.sortBy(recency).reverse

    val primaryRows = primary.map(o => resumeRow(o, primaryLabel(o)))
    val earlierRows =
      if expanded then earlier.map(o => resumeRow(o, earlierLabel(o)))
      else expanderRow(earlier.size, "earlier occurrence")
    val oneShotRows =
      if expanded then oneShotSorted.map(o => resumeRow(o, oneShotLabel(o)))
      else
        expanderRow(
          oneShotSorted.size,
          "one-shot session",
          " (reviews, plan steps)"
        )

    primaryRows ++ earlierRows ++ oneShotRows

  /** Parses `session.lastActiveAt` — always a valid `Instant` from the writer
    * (`clock().toString`), but falls back to the epoch rather than throwing on
    * a hand-edited or future-schema manifest, so one bad row can't take down
    * the whole picker.
    */
  private def recency(o: Occurrence): Instant =
    Try(Instant.parse(o.session.lastActiveAt)).getOrElse(Instant.EPOCH)

  private def resumeRow(o: Occurrence, label: String): Choice[PickerRow] =
    Choice(
      PickerRow.Resume(SessionSelection(o.run.manifest, o.session)),
      label,
      disabledReason = ResumeCommand.staticGate(o.session).left.toOption
    )

  /** A single "show N ..." expander row, or `Nil` when there's nothing to
    * reveal — omitting the row entirely rather than showing "show 0 ...".
    */
  private def expanderRow(
      count: Int,
      noun: String,
      suffix: String = ""
  ): List[Choice[PickerRow]] =
    if count == 0 then Nil
    else
      val plural = if count == 1 then "" else "s"
      List(Choice(PickerRow.ShowMore, s"… show $count $noun$plural$suffix"))

  /** `★ <sessionName> — latest (stage: <stage>) [<harness>]`, or `(no stage
    * yet)` when the durable session hasn't entered a stage (rare — custom flows
    * only, per research 08 item 7+8 §5). Falls back to the agent name if a
    * malformed manifest somehow has `kind == "durable"` without a
    * `sessionName`.
    */
  private def primaryLabel(o: Occurrence): String =
    val name = o.session.sessionName.getOrElse(o.session.agent)
    val stage = o.session.stage.fold("no stage yet")(s => s"stage: $s")
    val harness = harnessSettingsName(o.session.harness)
    val crashedSuffix = if o.run.crashed then " (crashed)" else ""
    s"★ $name — latest ($stage) [$harness]$crashedSuffix"

  /** `<sessionName> — stage <stage> [<harness>] (earlier occurrence)`, shown
    * only when the picker is expanded.
    */
  private def earlierLabel(o: Occurrence): String =
    val name = o.session.sessionName.getOrElse(o.session.agent)
    val stage = o.session.stage.fold("")(s => s" — stage $s")
    val harness = harnessSettingsName(o.session.harness)
    val crashedSuffix = if o.run.crashed then " (crashed)" else ""
    s"$name$stage [$harness] (earlier occurrence)$crashedSuffix"

  /** `<agent> (<role>) — stage <stage> [<harness>] (one-shot)`, omitting the
    * role/stage segments when absent; shown only when the picker is expanded.
    */
  private def oneShotLabel(o: Occurrence): String =
    val role = o.session.role.fold("")(r => s" ($r)")
    val stage = o.session.stage.fold("")(s => s" — stage $s")
    val harness = harnessSettingsName(o.session.harness)
    val crashedSuffix = if o.run.crashed then " (crashed)" else ""
    s"${o.session.agent}$role$stage [$harness] (one-shot)$crashedSuffix"

  /** The settings-file harness name (`claude`, `codex`, …) for a manifest's
    * [[BackendTag.wireName]] string, falling back to the raw string for an
    * unrecognised one (the row itself is disabled in that case, so this is
    * display-only).
    */
  private def harnessSettingsName(wireName: String): String =
    BackendTag
      .fromWireName(wireName)
      .flatMap(AgentSpec.harnessNameFor.get)
      .getOrElse(wireName)

  /** Prompts which tier to customize a built-in flow into (Project or Global) —
    * the tier [[EditAction.customizeThenEdit]] then copies the flow into and
    * opens.
    */
  private def promptCustomizeTier(
      ui: ShellUi,
      flow: DiscoveredFlow
  ): Option[CustomizeTier] =
    val globalFlows = GlobalSettings.defaultFlows
    val tierChoices = List(
      Choice(CustomizeTier.Project, "Project (.orca/flows/)"),
      Choice(CustomizeTier.Global, s"Global ($globalFlows)")
    )
    ui.select(
      s"'${flow.name}' is built-in — customize it into",
      tierChoices
    ) match
      case UiOutcome.Cancelled      => None
      case UiOutcome.Selected(tier) => Some(tier)

  /** Lists flows across the three tiers via [[FlowResolution.list]] — any
    * failure (a committed symlink guard tripping, or built-in extraction
    * hitting a full-disk/permission error) is reported and the caller gets
    * `None`, same as Cancelled, so the menu redraws instead of the shell
    * crashing.
    */
  private def selectFlow(ui: ShellUi, title: String): Option[DiscoveredFlow] =
    val flows = FlowResolution.list(os.pwd) match
      case Left(message) =>
        ShellOutput.error(message)
        None
      case Right(fs) => Some(fs)
    flows.flatMap: fs =>
      ui.select(title, fs.map(flowChoice)) match
        case UiOutcome.Cancelled      => None
        case UiOutcome.Selected(flow) => Some(flow)

  /** `name — description [origin]`, with a `[shadows ...]` suffix when the
    * winner shadows a lower-precedence tier (ADR 0021 §5).
    */
  private def flowChoice(flow: DiscoveredFlow): Choice[DiscoveredFlow] =
    val shadows =
      if flow.shadows.isEmpty then ""
      else s" [shadows ${flow.shadows.map(originLabel).mkString(", ")}]"
    val description = flow.description.getOrElse("(no description)")
    val label =
      s"${flow.name} — $description [${originLabel(flow.origin)}]$shadows"
    Choice(flow, label)

  private def originLabel(origin: FlowOrigin): String = origin match
    case FlowOrigin.Project => "project"
    case FlowOrigin.Global  => "global"
    case FlowOrigin.BuiltIn => "built-in"

  /** "Re-discover project stack settings" (ADR 0021 §8/§4, feedback item 4):
    * [[StackAction.status]] does the guarded read/parse (a missing file, or one
    * with no stack lines already, is a no-op with a one-line explanation; an
    * unparseable file aborts instead of being surgically edited blind); on a
    * live status this renders it, confirms, and calls [[StackAction.clear]] —
    * which strips the stack lines ([[SettingsFile.stripStackLines]]) so the
    * next flow run's own `hasStackLines`-driven check
    * (`FlowLifecycle.readSettings`) fires discovery again. `workDir` is
    * explicit (rather than reading `os.pwd` itself), same as [[sessionRows]],
    * so tests can point it at a temp dir.
    */
  private[shell] def rediscoverStack(ui: ShellUi, workDir: os.Path): Unit =
    StackAction.status(workDir) match
      case Left(message) => ShellOutput.error(message)
      case Right(StackStatus.NoSettings | StackStatus.NoStackLines) =>
        noStackSettingsToClear()
      case Right(StackStatus.Present(stack, content)) =>
        ShellOutput.info("Current stack settings:")
        println(renderStackSettings(stack))
        ui.confirm(
          "Clear discovered stack settings so the next flow run " +
            "re-detects them?",
          default = false
        ) match
          case UiOutcome.Selected(true) =>
            StackAction.clear(workDir, content)
            ShellOutput.info(
              "cleared — the next flow run will re-discover " +
                "format/lint/test"
            )
          case _ => ()

  private def noStackSettingsToClear(): Unit =
    ShellOutput.info(
      "no stack settings to clear (discovery runs on the next flow)"
    )

  /** ` format: <cmd>` per line for each non-empty [[StackSettings]] key, in
    * format/lint/test order — display only for [[rediscoverStack]]'s confirm
    * prompt; a key with only demoted/unset (commented) lines and no live
    * command shows nothing here even though it still counts for
    * [[SettingsFile.hasStackLines]].
    */
  private[shell] def renderStackSettings(stack: StackSettings): String =
    val rows =
      List("format" -> stack.format, "lint" -> stack.lint, "test" -> stack.test)
        .flatMap((key, commands) => commands.map(cmd => s"  $key: $cmd"))
    if rows.isEmpty then
      "  (no live commands — only commented-out/unset stack lines on file)"
    else rows.mkString("\n")
