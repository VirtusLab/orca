package orca.shell

import org.jline.terminal.Terminal
import orca.settings.GlobalSettings
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
import orca.shell.create.{CreateTarget, CreateTier, FlowAuthoring}
import orca.shell.flows.{DiscoveredFlow, FlowOrigin}
import orca.shell.run.FallbackPolicy
import orca.shell.sessions.{ManifestReader, RecordedRun, SessionPicker}
import orca.shell.ui.{Choice, ShellOutput, ShellUi, UiOutcome}
import orca.shell.wizard.{FirstRun, FirstRunStatus, Wizard}
import orca.subprocess.{GitRepoProbe, PathProbe}
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
    val terminal = ShellUi.buildTerminal()
    try
      val tty = ShellUi.isInteractive(terminal)
      // Clear stale mid-line progress bytes so the banner starts clean.
      print(ShellOutput.AnsiClearLine)
      ShellOutput.info(s"orca shell ${ShellVersion.value}")
      // scala-cli/coursier's download progress can still have several stale
      // lines sitting below the banner (see AnsiClearBelow) — wipe them before
      // the first wizard/menu paint. Non-tty output (NumberedUi) skips this:
      // there's no terminal to erase, only a redirected stream to pollute.
      if tty then print(ShellOutput.AnsiClearBelow)
      val ui = ShellUi.make(terminal)
      val globalSettingsPath = GlobalSettings.default
      val wizard = Wizard(ui, PathProbe.resolves(_, os.pwd), globalSettingsPath)
      runWizardIfFirstRun(wizard, globalSettingsPath)
      loop(ui, wizard, terminal, tty)
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

  /** Selects a flow, prompts for the task text and whether to create a branch,
    * then hands off to [[RunAction.run]]. Verbose is not exposed here in v1 — a
    * later task can add a verbose confirm alongside session tracking.
    */
  private def runFlow(ui: ShellUi, terminal: Terminal): Unit =
    for
      flow <- listFlows().flatMap(
        pickFlow(ui, "Run which flow?", _, promoteByName(FlagshipFlow, _))
      )
      task <- promptTask(ui)
      createBranch <- promptCreateBranch(ui)
    do
      val opts = RunAction.RunOptions(
        verbose = false,
        skipBranch = !createBranch,
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

  /** "Create a new branch for this run?" confirm (default yes — Enter keeps
    * today's behavior); declining runs in skip-branch mode instead (ADR 0018
    * amendment), continuing on the current branch — the handoff-from-harness
    * case where the user already planned work on a branch carrying plan files.
    * `private[shell]` so a scripted-UI test can drive it directly.
    */
  private[shell] def promptCreateBranch(ui: ShellUi): Option[Boolean] =
    ui.confirm(
      "Create a new branch for this run? (choosing 'no': the flow makes " +
        "its changes on the current branch)",
      default = true
    ) match
      case UiOutcome.Cancelled   => None
      case UiOutcome.Selected(v) => Some(v)

  /** New-flow authoring: tier → goal → filename (defaulted from the goal's
    * [[suggestFilenameForGoal]] slug), then hands off to
    * [[AuthorAction.create]] — which runs the built-in
    * `implement-interactive.sc` flow with the authoring prompt as its task, so
    * the configured planning/coding/review agents (and their model pins) do the
    * writing, same as any other flow run. Cancelling any prompt, or a filename
    * collision, aborts back to the menu without launching anything. Reachable
    * via [[MenuItem.CreateFlow]]; [[MenuItem.ForkFlow]] is the sibling entry
    * point for [[createForkFlow]]. `private[shell]` so a scripted-UI test can
    * drive it directly.
    */
  private[shell] def createNewFlow(
      ui: ShellUi,
      terminal: Terminal,
      gitProbe: os.Path => Boolean = GitRepoProbe.isInsideWorkTree
  ): Unit =
    val workDir = os.pwd
    val globalFlows = GlobalSettings.defaultFlows
    for
      tier <- pickTier(ui, "Where should the new flow be saved:", globalFlows)
      _ <- requireGitRepoForGlobalTier(tier, workDir, gitProbe)
      goal <- promptDescription(ui, "Describe what the flow should do")
      target <- promptFlowTarget(
        ui,
        tier,
        workDir,
        globalFlows,
        default = Some(FlowAuthoring.suggestFilenameForGoal(goal))
      )
    do
      AuthorAction
        .create(goal, AuthorParams(tier, target), workDir, ui, terminal)
        .discard

  /** Fork-an-existing-flow authoring: pick the source flow from every tier
    * (same rows View/Edit use) → describe the changes → tier for the fork's
    * target → filename (defaulted from [[FlowAuthoring.forkFilenameDefault]]),
    * then hands off to [[AuthorAction.fork]] — same flow-based launch as
    * [[createNewFlow]]. `private[shell]` so a scripted-UI test can drive it
    * directly.
    */
  private[shell] def createForkFlow(
      ui: ShellUi,
      terminal: Terminal,
      gitProbe: os.Path => Boolean = GitRepoProbe.isInsideWorkTree
  ): Unit =
    val workDir = os.pwd
    val globalFlows = GlobalSettings.defaultFlows
    for
      source <- selectFlow(ui, "Fork which flow:")
      changes <- promptDescription(ui, "Describe the changes for the fork")
      tier <- pickTier(ui, "Where should the fork be saved:", globalFlows)
      _ <- requireGitRepoForGlobalTier(tier, workDir, gitProbe)
      target <- promptFlowTarget(
        ui,
        tier,
        workDir,
        globalFlows,
        default = Some(FlowAuthoring.forkFilenameDefault(source.name))
      )
    do
      AuthorAction
        .fork(
          source,
          changes,
          AuthorParams(tier, target),
          workDir,
          ui,
          terminal
        )
        .discard

  /** [[FlowAuthoring.requireGitRepoForGlobalTier]], reported and turned into an
    * abort (`None`) rather than a re-prompt — a missing repo isn't something
    * the user fixes by answering differently, so `createNewFlow`/
    * `createForkFlow` stop right here instead of asking anything else.
    */
  private def requireGitRepoForGlobalTier(
      tier: CreateTier,
      workDir: os.Path,
      gitProbe: os.Path => Boolean
  ): Option[Unit] =
    FlowAuthoring.requireGitRepoForGlobalTier(tier, workDir, gitProbe) match
      case Left(message) =>
        ShellOutput.error(message)
        None
      case Right(()) => Some(())

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
    * path via [[FlowAuthoring.validateFileName]] +
    * [[FlowAuthoring.safePrepareTarget]] — the same guard `AuthorCli`'s
    * `create`/`fork` use — re-prompting with the same `default` on an invalid
    * name or a collision (printing the reason first) rather than aborting the
    * whole create-flow attempt, or (before this guard existed) crashing the
    * shell on a name like `sub/x` that os-lib rejects with a raw exception. The
    * harness writes the flow file itself, so an existing file at the target
    * path is never overwritten.
    */
  @tailrec private[shell] def promptFlowTarget(
      ui: ShellUi,
      tier: CreateTier,
      workDir: os.Path,
      globalFlows: os.Path,
      default: Option[String]
  ): Option[CreateTarget] =
    ui.input("Flow filename:", default) match
      case UiOutcome.Cancelled => None
      case UiOutcome.Selected(rawName) =>
        prepareValidTarget(tier, rawName, workDir, globalFlows) match
          case Left(message) =>
            ShellOutput.error(message)
            promptFlowTarget(ui, tier, workDir, globalFlows, default)
          case Right(target) => Some(target)

  private def prepareValidTarget(
      tier: CreateTier,
      rawName: String,
      workDir: os.Path,
      globalFlows: os.Path
  ): Either[String, CreateTarget] =
    for
      _ <- FlowAuthoring.validateFileName(rawName)
      target <- FlowAuthoring.safePrepareTarget(
        tier,
        rawName,
        workDir,
        globalFlows
      )
    yield target

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

  /** orca's flagship built-in flow — promoted to the front of the run picker
    * ([[runFlow]]) since the interactive select has no cursor preselection
    * (`ConsoleUiShell.select`'s scaladoc), so first position is what actually
    * reads as the default.
    */
  private[shell] val FlagshipFlow = "implement.sc"

  /** Moves the flow named `name` to the front, leaving every other flow's
    * relative order unchanged; a no-op (alphabetical order preserved) if no
    * flow has that name — e.g. it was deleted from every tier.
    */
  private[shell] def promoteByName(
      name: String,
      flows: List[DiscoveredFlow]
  ): List[DiscoveredFlow] =
    val (front, rest) = flows.partition(_.name == name)
    front ++ rest

  /** Lists flows across the three tiers via [[FlowResolution.list]] — any
    * failure (a committed symlink guard tripping, or built-in extraction
    * hitting a full-disk/permission error) is reported and the caller gets
    * `None`. Shared by [[selectFlow]] and [[runFlow]], the one caller that
    * needs [[pickFlow]]'s `reorder` (promoting [[FlagshipFlow]]) instead of its
    * default alphabetical order.
    */
  private def listFlows(): Option[List[DiscoveredFlow]] =
    FlowResolution.list(os.pwd) match
      case Left(message) =>
        ShellOutput.error(message)
        None
      case Right(fs) => Some(fs)

  /** Shows `flows` via [[pickFlow]] at their default (alphabetical) order — any
    * discovery failure is reported and the caller gets `None`, same as
    * Cancelled, so the menu redraws instead of the shell crashing.
    */
  private def selectFlow(ui: ShellUi, title: String): Option[DiscoveredFlow] =
    listFlows().flatMap(pickFlow(ui, title, _))

  /** Applies `reorder` to `flows` and shows the result via `ui.select` — the
    * half of [[selectFlow]] downstream of flow discovery, so a test can drive
    * the reorder-then-show wiring (e.g. that the run picker's promotion of
    * [[FlagshipFlow]] actually reaches `ui.select` first) against a fixed list.
    */
  private[shell] def pickFlow(
      ui: ShellUi,
      title: String,
      flows: List[DiscoveredFlow],
      reorder: List[DiscoveredFlow] => List[DiscoveredFlow] = identity
  ): Option[DiscoveredFlow] =
    ui.select(title, reorder(flows).map(flowChoice)) match
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

  /** "Re-discover project stack settings" (ADR 0021 §8/§4):
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
