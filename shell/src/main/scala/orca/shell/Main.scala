package orca.shell

import org.jline.terminal.Terminal
import orca.agents.BackendTag
import orca.settings.{AgentSpec, GlobalSettings}
import orca.shell.actions.{
  AuthorAction,
  AuthorParams,
  EditAction,
  FlowResolution,
  RunAction,
  SessionAction,
  StackAction,
  StackStatus,
  ViewAction
}
import orca.shell.cli.{Cli, CliHelp}
import orca.shell.create.{FlowAuthoring, CreateTarget, CreateTier}
import orca.shell.flows.{DiscoveredFlow, FlowOrigin}
import orca.shell.run.FallbackPolicy
import orca.shell.sessions.{ManifestReader, RecordedRun, SessionPicker}
import orca.shell.ui.{Choice, ShellOutput, ShellUi, UiOutcome}
import orca.shell.wizard.{FirstRun, FirstRunStatus, Wizard}
import orca.subprocess.PathProbe
import ox.discard

import scala.annotation.tailrec

/** Entry point for the `orca` shell executable (ADR 0021). No-arg → the
  * interactive shell below, unchanged. Any argv → the non-interactive CLI
  * surface (ADR 0021 §10, `cli/Cli.scala`): a curated `--help`/`--version`
  * handled here, a known subcommand dispatched to [[Cli.dispatch]] with its
  * returned code the sole `sys.exit` call, anything else a usage error. The CLI
  * path never prints the banner or runs the first-run wizard — both are
  * exclusive to the interactive shell.
  */
object Main:

  def main(args: Array[String]): Unit =
    args.headOption match
      case None => runInteractiveShell()
      case Some("--help") | Some("-h") | Some("help") =>
        println(CliHelp.topLevel)
        sys.exit(0)
      case Some("--version") | Some("-V") =>
        println(ShellVersion.value)
        sys.exit(0)
      case Some(token) if Cli.commandNames(token) =>
        sys.exit(Cli.dispatch(args.toIndexedSeq))
      case Some(token) =>
        Console.err.println(
          s"orca: unknown command '$token' — run 'orca --help'"
        )
        sys.exit(2)

  private def runInteractiveShell(): Unit =
    // scala-cli's dependency-download progress writes bare `\r` (no line
    // clear) before handing control here, so the cursor can still be mid-line
    // over that stale text; clear it first so the banner starts on a clean
    // line instead of appending to it.
    print("[2K\r")
    ShellOutput.info(s"orca shell ${ShellVersion.value}")
    val terminal = ShellUi.buildTerminal()
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
    val (runs, warnings) = ManifestReader.list(os.pwd, ManifestReader.pidAlive)
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

  /** Prints the chosen flow's source (highlighted when `tty`) and returns — the
    * menu redraws on the next loop iteration, so no pager is needed (ADR 0021
    * §6).
    */
  private def viewFlow(ui: ShellUi, tty: Boolean): Unit =
    selectFlow(ui, "View which flow?").foreach: flow =>
      println(ViewAction.render(flow, tty))

  /** Opens the chosen flow in `$VISUAL`/`$EDITOR`/`vi`. Project and global
    * flows are edited in place ([[EditAction.editInPlace]]); a built-in is
    * never edited in its cache copy, so [[pickTier]] plus
    * [[EditAction.customizeThenEdit]] copy it into a tier first.
    */
  private def editFlow(ui: ShellUi, terminal: Terminal): Unit =
    selectFlow(ui, "Edit which flow?").foreach: flow =>
      if flow.origin != FlowOrigin.BuiltIn then
        EditAction.editInPlace(terminal, flow.path).discard
      else
        pickTier(
          ui,
          s"'${flow.name}' is built-in — customize it into",
          GlobalSettings.defaultFlows
        ).foreach: tier =>
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
    * goal's [[suggestFilenameForGoal]] slug) → harness+yolo, then hands off to
    * [[AuthorAction.create]]. Cancelling any prompt, or a filename collision,
    * aborts back to the menu without launching anything. Reachable via
    * [[MenuItem.CreateFlow]]; [[MenuItem.ForkFlow]] (item 10) is the sibling
    * entry point for [[createForkFlow]].
    */
  private def createNewFlow(ui: ShellUi, terminal: Terminal): Unit =
    val workDir = os.pwd
    val globalFlows = GlobalSettings.defaultFlows
    for
      tier <- pickTier(ui, "Where should the new flow be saved:", globalFlows)
      goal <- promptDescription(ui, "Describe what the flow should do")
      target <- promptFlowTarget(
        ui,
        tier,
        workDir,
        globalFlows,
        default = Some(FlowAuthoring.suggestFilenameForGoal(goal))
      )
      (backend, yolo) <- selectHarnessAndYolo(ui)
    do
      val params = AuthorParams(tier, target, backend, yolo)
      AuthorAction.create(goal, params, workDir, ui, terminal).discard

  /** Fork-an-existing-flow authoring (item 10): pick the source flow from every
    * tier (same rows View/Edit use) → describe the changes → tier for the
    * fork's target → filename (defaulted from
    * [[FlowAuthoring.forkFilenameDefault]]) → harness+yolo, then hands off to
    * [[AuthorAction.fork]].
    */
  private def createForkFlow(ui: ShellUi, terminal: Terminal): Unit =
    val workDir = os.pwd
    val globalFlows = GlobalSettings.defaultFlows
    for
      source <- selectFlow(ui, "Fork which flow:")
      changes <- promptDescription(ui, "Describe the changes for the fork")
      tier <- pickTier(ui, "Where should the fork be saved:", globalFlows)
      target <- promptFlowTarget(
        ui,
        tier,
        workDir,
        globalFlows,
        default = Some(FlowAuthoring.forkFilenameDefault(source.name))
      )
      (backend, yolo) <- selectHarnessAndYolo(ui)
    do
      val params = AuthorParams(tier, target, backend, yolo)
      AuthorAction.fork(source, changes, params, workDir, ui, terminal).discard

  /** The Project/Global target-tier picker, shared by new-flow authoring, fork
    * authoring, and customizing a built-in into a tier — same two choices every
    * time, only the `title` differs.
    */
  private def pickTier(
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

  /** Prompts for the flow's filename (pre-filled with `default`, e.g. the
    * goal's suggested slug or the fork's `-fork.sc` suggestion — either way
    * editable, per `ui.input`'s default-hint path) and resolves it to a target
    * path via [[FlowAuthoring.prepareTarget]], re-prompting with the same
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
        FlowAuthoring.prepareTarget(tier, rawName, workDir, globalFlows) match
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
    val default = FlowAuthoring.configuredCodingAgent(GlobalSettings.default)
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
    s"$name — ${Wizard.pathStatus(probe(name))}"

  /** Prompts among every session across `runs` and resumes the chosen one,
    * printing its identity — including `workDir` — before the resume exec
    * ([[SessionAction.identityNotice]], ADR 0021 §10; the CLI's own resume
    * paths print the same notice). Picking the expander re-renders the same
    * picker with `expanded = true`; there is no way back to the collapsed view
    * short of re-opening the menu item, which is fine — the picker is re-read
    * from disk on every open anyway. A cancelled prompt, or `runs` being empty
    * (unreachable via the menu today, since the item is disabled then, but
    * harmless), is a silent no-op.
    */
  private def continueSession(
      ui: ShellUi,
      terminal: Terminal,
      runs: List[RecordedRun],
      expanded: Boolean = false
  ): Unit =
    ui.select(
      "Continue which session?",
      SessionPicker.sessionRows(runs, expanded)
    ) match
      case UiOutcome.Cancelled => ()
      case UiOutcome.Selected(SessionPicker.PickerRow.ShowMore) =>
        continueSession(ui, terminal, runs, expanded = true)
      case UiOutcome.Selected(SessionPicker.PickerRow.Resume(selection)) =>
        ShellOutput.info(
          SessionAction.identityNotice(
            selection,
            SessionPicker.harnessSettingsName(selection.session.harness)
          )
        )
        SessionAction.resume(terminal, selection) match
          case Left(message) => ShellOutput.error(message)
          case Right(_)      => ()

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
      else s" [shadows ${flow.shadows.map(_.originLabel).mkString(", ")}]"
    val description = flow.description.getOrElse("(no description)")
    val label =
      s"${flow.name} — $description [${flow.origin.originLabel}]$shadows"
    Choice(flow, label)

  /** "Re-discover project stack settings" (ADR 0021 §8/§4, feedback item 4):
    * [[StackAction.status]] does the guarded read/parse (a missing file, or one
    * with no stack lines already, is a no-op with a one-line explanation; an
    * unparseable file aborts instead of being surgically edited blind); on a
    * live status [[StackAction.clearIfConfirmed]] renders it, confirms, and
    * calls [[StackAction.clear]] — which strips the stack lines
    * ([[SettingsFile.stripStackLines]]) so the next flow run's own
    * `hasStackLines`-driven check (`FlowLifecycle.readSettings`) fires
    * discovery again. `workDir` is explicit (rather than reading `os.pwd`
    * itself) so tests can point it at a temp dir.
    */
  private[shell] def rediscoverStack(ui: ShellUi, workDir: os.Path): Unit =
    StackAction.status(workDir) match
      case Left(message) => ShellOutput.error(message)
      case Right(StackStatus.NoSettings | StackStatus.NoStackLines) =>
        ShellOutput.info(StackAction.noSettingsMessage)
      case Right(StackStatus.Present(stack, content)) =>
        StackAction.clearIfConfirmed(
          workDir,
          stack,
          content,
          () =>
            ui.confirm(StackAction.clearConfirmPrompt, default = false) match
              case UiOutcome.Selected(true) => true
              case _                        => false
        )
