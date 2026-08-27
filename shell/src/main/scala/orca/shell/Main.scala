package orca.shell

import org.jline.terminal.Terminal
import orca.settings.GlobalSettings
import orca.shell.actions.{
  AuthorAction,
  AuthorParams,
  ConfigSummary,
  EditAction,
  FlowResolution,
  RunAction,
  SessionAction,
  SettingsEditAction,
  StackAction,
  StackStatus,
  ViewAction
}
import orca.shell.cli.{Cli, CliHelp}
import orca.shell.create.{CreateTarget, CreateTier, FlowAuthoring}
import orca.shell.flows.{DiscoveredFlow, FlowEditor, FlowOrigin}
import orca.shell.resume.{InterruptedRun, ResumeDetector}
import orca.shell.run.{FallbackPolicy, FlowFlags, LaunchResult}
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
      printConfigSummary(globalSettingsPath, os.pwd)
      loop(ui, wizard, globalSettingsPath, terminal, tty)
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
    * `ResumeDetector.detect` is likewise re-evaluated every redraw (ADR 0021 §3
    * amendment) — cheap (one dir listing plus one small file read) and
    * consistent with Continue's own re-read. The `branch:` line
    * ([[ConfigSummary.branchLine]]) is printed here for the same reason: a flow
    * run started from this menu can leave HEAD on a new branch, so it is
    * re-read per redraw rather than printed once with the startup summary.
    */
  @tailrec private def loop(
      ui: ShellUi,
      wizard: Wizard,
      globalSettingsPath: os.Path,
      terminal: Terminal,
      tty: Boolean
  ): Unit =
    val (runs, warnings) = ManifestReader.list(os.pwd, ManifestReader.pidAlive)
    warnings.foreach(ShellOutput.info)
    val continueSessionCount =
      runs.headOption.map(_.manifest.sessions.size)
    val resumeOffer = ResumeDetector.detect(os.pwd)
    ConfigSummary.branchLine(os.pwd).foreach(ShellOutput.info)
    ui.select(
      "orca shell",
      MainMenu.choices(continueSessionCount, resumeOffer)
    ) match
      case UiOutcome.Cancelled                      => ()
      case UiOutcome.Selected(MenuItem.Exit)        => ()
      case UiOutcome.Selected(MenuItem.Reconfigure) =>
        // Reprint the summary only when the wizard actually wrote new values
        // (`run` returns false on cancel) — the lines would otherwise claim a
        // change that didn't happen.
        if wizard.run(reconfigure = true) then
          printConfigSummary(globalSettingsPath, os.pwd)
        loop(ui, wizard, globalSettingsPath, terminal, tty)
      case UiOutcome.Selected(MenuItem.ResumeRun) =>
        // Only ever selectable when `resumeOffer` is `Some` — the item is
        // absent from the menu otherwise (`MainMenu.choices`); `.foreach` is
        // defensive, not a real branch.
        resumeOffer.foreach(run => resumeInterruptedRun(ui, terminal, run))
        loop(ui, wizard, globalSettingsPath, terminal, tty)
      case UiOutcome.Selected(MenuItem.EditSettings) =>
        editSettings(ui, terminal, globalSettingsPath)
        loop(ui, wizard, globalSettingsPath, terminal, tty)
      case UiOutcome.Selected(MenuItem.RediscoverStack) =>
        rediscoverStack(ui, os.pwd)
        loop(ui, wizard, globalSettingsPath, terminal, tty)
      case UiOutcome.Selected(MenuItem.ViewFlow) =>
        viewFlow(ui, tty)
        loop(ui, wizard, globalSettingsPath, terminal, tty)
      case UiOutcome.Selected(MenuItem.EditFlow) =>
        editFlow(ui, terminal)
        loop(ui, wizard, globalSettingsPath, terminal, tty)
      case UiOutcome.Selected(MenuItem.RunFlow) =>
        runFlow(ui, terminal)
        loop(ui, wizard, globalSettingsPath, terminal, tty)
      case UiOutcome.Selected(MenuItem.CreateFlow) =>
        createNewFlow(ui, terminal)
        loop(ui, wizard, globalSettingsPath, terminal, tty)
      case UiOutcome.Selected(MenuItem.ForkFlow) =>
        createForkFlow(ui, terminal)
        loop(ui, wizard, globalSettingsPath, terminal, tty)
      case UiOutcome.Selected(MenuItem.ContinueSession) =>
        continueSession(ui, terminal, runs)
        loop(ui, wizard, globalSettingsPath, terminal, tty)

  /** The two-line startup configuration summary (ADR 0021 §4/§8,
    * [[ConfigSummary]]) — printed once right after the banner (and after the
    * first-run wizard, if it ran) and again after a completed Re-configure, so
    * the user sees what they'd be reconfiguring before picking that menu item.
    */
  private[shell] def printConfigSummary(
      globalSettingsPath: os.Path,
      workDir: os.Path
  ): Unit =
    ShellOutput.info(ConfigSummary.agentsLine(globalSettingsPath, workDir))
    ShellOutput.info(ConfigSummary.stackLine(workDir))

  /** Prints the chosen flow's source (highlighted when `tty`) and returns — the
    * menu redraws on the next loop iteration, so no pager is needed (ADR 0021
    * §6).
    */
  private def viewFlow(ui: ShellUi, tty: Boolean): Unit =
    selectFlow(ui, "View which flow?").foreach: flow =>
      println(ViewAction.render(flow, tty))

  /** Edit-a-flow: pick the flow, then ask how (ADR 0021 §6 amendment) —
    * [[editFlowByHand]] is today's original path unchanged; [[editFlowByAgent]]
    * is the new one. `spawnEditor` is injectable, like [[editSettings]]'s own
    * seam, so a test can fake the editor exiting instead of spawning a real
    * subprocess; `workDir`/`globalFlows` are likewise explicit (rather than
    * reading `os.pwd`/[[GlobalSettings.defaultFlows]] internally), so a test
    * can point the built-in-customize branch at temp dirs. `private[shell]` so
    * a scripted-UI test can drive it directly.
    */
  private[shell] def editFlow(
      ui: ShellUi,
      terminal: Terminal,
      workDir: os.Path = os.pwd,
      globalFlows: os.Path = GlobalSettings.defaultFlows,
      spawnEditor: (Terminal, os.Path) => Int = EditAction.editInPlace
  ): Unit =
    selectFlow(ui, "Edit which flow?").foreach: flow =>
      pickChangeMode(ui).foreach:
        case ChangeMode.Hand =>
          editFlowByHand(ui, terminal, flow, workDir, globalFlows, spawnEditor)
        case ChangeMode.Agent =>
          editFlowByAgent(ui, terminal, flow, workDir, globalFlows)

  /** Opens the chosen flow in `$VISUAL`/`$EDITOR`/`vi`. Project and global
    * flows are edited in place; a built-in is never edited in its cache copy,
    * so [[pickTier]] plus [[FlowEditor.customizeTarget]] copy it into a tier
    * first.
    */
  private def editFlowByHand(
      ui: ShellUi,
      terminal: Terminal,
      flow: DiscoveredFlow,
      workDir: os.Path,
      globalFlows: os.Path,
      spawnEditor: (Terminal, os.Path) => Int
  ): Unit =
    if flow.origin != FlowOrigin.BuiltIn then
      spawnEditor(terminal, flow.path).discard
    else
      pickTier(ui, builtInCustomizeTitle(flow), globalFlows).foreach: tier =>
        FlowEditor.customizeTarget(flow, tier, workDir, globalFlows) match
          case Left(message) => ShellOutput.error(message)
          case Right(path)   => spawnEditor(terminal, path).discard

  /** Edit-a-flow, agent mode (ADR 0021 §6/§9 amendment): describes the change,
    * runs it through [[AuthorAction.fork]]-style sandboxed authoring with
    * `target = the flow's own path` and `overwrite = true`, so success copies
    * the agent's result back OVER the original — an edit overwrites. A built-in
    * source is never overwritten directly (there's nothing to overwrite): it's
    * customized into a tier first, same picker as [[editFlowByHand]], and the
    * agent's changes land on that new copy instead.
    */
  private def editFlowByAgent(
      ui: ShellUi,
      terminal: Terminal,
      flow: DiscoveredFlow,
      workDir: os.Path,
      globalFlows: os.Path
  ): Unit =
    flow.origin match
      case FlowOrigin.BuiltIn =>
        pickTier(ui, builtInCustomizeTitle(flow), globalFlows).foreach: tier =>
          FlowEditor.customizeTarget(flow, tier, workDir, globalFlows) match
            case Left(message) => ShellOutput.error(message)
            case Right(targetPath) =>
              editByAgent(
                ui,
                terminal,
                flow.copy(path = targetPath),
                tier,
                workDir,
                globalFlows
              )
      case FlowOrigin.Project =>
        editByAgent(
          ui,
          terminal,
          flow,
          CreateTier.Project,
          workDir,
          globalFlows
        )
      case FlowOrigin.Global =>
        editByAgent(ui, terminal, flow, CreateTier.Global, workDir, globalFlows)

  /** Prompts for the changes and runs the overwrite-in-place authoring flow
    * against `flow`'s own path — shared by both [[editFlowByAgent]] branches
    * once the tier and (for a built-in source) the customized copy are
    * resolved.
    */
  private def editByAgent(
      ui: ShellUi,
      terminal: Terminal,
      flow: DiscoveredFlow,
      tier: CreateTier,
      workDir: os.Path,
      globalFlows: os.Path
  ): Unit =
    promptDescription(ui, "Describe the changes for the edit").foreach:
      changes =>
        val target =
          CreateTarget(
            flow.path,
            FlowAuthoring.tierCwd(tier, workDir, globalFlows)
          )
        AuthorAction
          .fork(
            flow,
            changes,
            AuthorParams(tier, target, overwrite = true),
            ui,
            terminal
          )
          .discard

  /** The customize-tier picker's title, shared by [[editFlowByHand]] and
    * [[editFlowByAgent]]'s built-in branch — same wording either way, since
    * both mean "copy this built-in into a tier before touching it".
    */
  private def builtInCustomizeTitle(flow: DiscoveredFlow): String =
    s"'${flow.name}' is built-in — customize it into:"

  /** "Edit settings": tier prompt, create the file from its template if absent
    * ([[SettingsEditAction.ensureExists]]), open it via the same editor-spawn
    * machinery "Edit a flow" uses ([[EditAction.editInPlace]], injectable as
    * `spawnEditor` so a test can fake the editor exiting without a real
    * subprocess), then re-parse it ([[SettingsEditAction.validate]]): malformed
    * reports a non-fatal warning and returns to the menu, valid reprints the
    * startup config summary ([[ConfigSummary]], mirroring Reconfigure) so the
    * edit's effect is visible immediately. `workDir` is explicit (rather than
    * reading `os.pwd` itself, like [[rediscoverStack]]) so a test can point it
    * at a temp dir; `private[shell]` so a scripted-UI test can drive it
    * directly.
    */
  private[shell] def editSettings(
      ui: ShellUi,
      terminal: Terminal,
      globalSettingsPath: os.Path,
      workDir: os.Path = os.pwd,
      spawnEditor: (Terminal, os.Path) => Int = EditAction.editInPlace
  ): Unit =
    pickSettingsTier(ui, globalSettingsPath).foreach: tier =>
      val path = SettingsEditAction.pathFor(tier, workDir, globalSettingsPath)
      SettingsEditAction.ensureExists(tier, path, workDir)
      spawnEditor(terminal, path).discard
      SettingsEditAction.validate(tier, workDir, globalSettingsPath) match
        case Left(error) => ShellOutput.error(error)
        case Right(_)    => printConfigSummary(globalSettingsPath, workDir)

  /** The Project/Global tier picker for [[editSettings]] — same two-choice
    * shape as [[pickTier]], but naming the settings file's own path in each
    * label instead of the flows directory.
    */
  private def pickSettingsTier(
      ui: ShellUi,
      globalSettingsPath: os.Path
  ): Option[CreateTier] =
    ui.select(
      "Edit settings for which tier:",
      List(
        Choice(CreateTier.Project, "Project (.orca/settings.properties)"),
        Choice(CreateTier.Global, s"Global ($globalSettingsPath)")
      )
    ) match
      case UiOutcome.Cancelled      => None
      case UiOutcome.Selected(tier) => Some(tier)

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
        flags = FlowFlags(
          verbose = false,
          skipBranch = !createBranch,
          keepChanges = false,
          worktree = false
        ),
        fallback = FallbackPolicy.Ask(ui)
      )
      RunAction.run(flow, task, opts, os.pwd, terminal).discard

  /** Resumes `run` (ADR 0021 §3 amendment): resolves its recorded flow name
    * against the current catalog and launches it with the recorded task text
    * verbatim — no re-prompting, so the text stays byte-identical to what the
    * interrupted run started with (the progress log's resume check keys on a
    * hash of it). Runs through the exact same launch path "Run a flow" uses
    * ([[RunAction.run]]/[[orca.shell.run.FlowLauncher]]): no branch prompt —
    * the resume happens on the current branch by design, and a resumed log's
    * `bindBranch` (`FlowLifecycle`) ignores `skipBranch` entirely, so
    * verbose/skip-branch/keep-changes are as correct here as any other value
    * would be. `worktree` is false only until the worktree-spanning scan lands:
    * a run found in a worktree has to be relaunched with it set, which is what
    * takes the relaunch back to the tree holding its progress log. `runAction`
    * is injectable, [[AuthorAction]]-style, so a test can record the call
    * instead of spawning a real subprocess.
    */
  private[shell] def resumeInterruptedRun(
      ui: ShellUi,
      terminal: Terminal,
      run: InterruptedRun,
      workDir: os.Path = os.pwd,
      runAction: (
          DiscoveredFlow,
          String,
          RunAction.RunOptions,
          os.Path,
          Terminal
          // Spelled as a lambda, not `RunAction.run`: the real method has a
          // trailing injectable `launch` of its own, which eta-expansion would
          // pull into this shape.
      ) => LaunchResult = RunAction.run(_, _, _, _, _)
  ): Unit =
    FlowResolution.resolve(run.flowName, workDir) match
      case Left(message) => ShellOutput.error(message)
      case Right(flow) =>
        val opts =
          RunAction.RunOptions(
            flags = FlowFlags(
              verbose = false,
              skipBranch = false,
              keepChanges = false,
              worktree = false
            ),
            fallback = FallbackPolicy.Ask(ui)
          )
        runAction(flow, run.userPrompt, opts, workDir, terminal).discard

  /** Prompts for the flow's task text, re-prompting on blank input — an empty
    * `userPrompt` reaches the flow's agent directly (branch naming, the coding
    * session's instructions), so it's rejected here rather than passed through
    * as a degenerate run.
    */
  @tailrec private def promptTask(ui: ShellUi): Option[String] =
    ui.inputMultiline("Describe the task for this flow run") match
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

  /** "How should the changes be made?" (ADR 0021 §6/§9 amendment) —
    * [[MainMenu.modeChoices]]'s hand-vs-agent prompt, shared by Edit/Create/
    * Fork.
    */
  private def pickChangeMode(ui: ShellUi): Option[ChangeMode] =
    ui.select("How should the changes be made?", MainMenu.modeChoices) match
      case UiOutcome.Cancelled      => None
      case UiOutcome.Selected(mode) => Some(mode)

  /** New-flow authoring: mode FIRST — it decides whether a goal (agent) or a
    * filename (hand) comes next. Reachable via [[MenuItem.CreateFlow]];
    * [[MenuItem.ForkFlow]] is the sibling entry point for [[createForkFlow]].
    * `private[shell]` so a scripted-UI test can drive it directly.
    */
  private[shell] def createNewFlow(
      ui: ShellUi,
      terminal: Terminal,
      workDir: os.Path = os.pwd,
      globalFlows: os.Path = GlobalSettings.defaultFlows,
      spawnEditor: (Terminal, os.Path) => Int = EditAction.editInPlace
  ): Unit =
    pickChangeMode(ui).foreach:
      case ChangeMode.Hand =>
        createNewFlowByHand(ui, terminal, workDir, globalFlows, spawnEditor)
      case ChangeMode.Agent =>
        createNewFlowByAgent(ui, terminal, workDir, globalFlows)

  /** Create+hand (ADR 0021 §9 amendment): tier, then a filename (there's no
    * goal to slug a default from), then a minimal compiling
    * [[FlowAuthoring.skeletonFlow]] is written and opened in the editor —
    * `spawnEditor` injectable like [[editSettings]]'s own seam.
    */
  private def createNewFlowByHand(
      ui: ShellUi,
      terminal: Terminal,
      workDir: os.Path,
      globalFlows: os.Path,
      spawnEditor: (Terminal, os.Path) => Int
  ): Unit =
    for
      tier <- pickTier(ui, "Where should the new flow be saved:", globalFlows)
      target <- promptNewFlowFilename(ui, tier, workDir, globalFlows)
    do
      os.write.over(
        target.flowPath,
        FlowAuthoring.skeletonFlow(ShellVersion.value)
      )
      spawnEditor(terminal, target.flowPath).discard

  /** Prompts for the new flow's filename, defaulted to `new-flow.sc` and
    * validated/collision-refused the same way the CLI's explicit `name`
    * argument is ([[FlowAuthoring.validateFileName]] +
    * [[FlowAuthoring.safePrepareTarget]]) — re-prompting on either problem
    * rather than aborting, since a taken or invalid name is easy to fix without
    * starting the whole flow over.
    */
  @tailrec private def promptNewFlowFilename(
      ui: ShellUi,
      tier: CreateTier,
      workDir: os.Path,
      globalFlows: os.Path
  ): Option[CreateTarget] =
    ui.input("Filename for the new flow", default = Some("new-flow.sc")) match
      case UiOutcome.Cancelled => None
      case UiOutcome.Selected(name) =>
        val prepared =
          for
            _ <- FlowAuthoring.validateFileName(name)
            target <- FlowAuthoring
              .safePrepareTarget(tier, name, workDir, globalFlows)
          yield target
        prepared match
          case Left(message) =>
            ShellOutput.error(message)
            promptNewFlowFilename(ui, tier, workDir, globalFlows)
          case Right(target) => Some(target)

  /** Create+agent: today's original path, unchanged — tier → goal, filename
    * auto-derived from the goal's [[FlowAuthoring.suggestFilenameForGoal]] slug
    * (uniquified on collision — never prompted for), then hands off to
    * [[AuthorAction.create]] — which runs the built-in `simple.sc` flow in a
    * throwaway [[orca.shell.create.AuthoringSandbox]], so the configured
    * planning/coding/review agents (and their model pins) do the writing
    * without ever touching the user's repository. Cancelling any prompt aborts
    * back to the menu without launching anything.
    */
  private def createNewFlowByAgent(
      ui: ShellUi,
      terminal: Terminal,
      workDir: os.Path,
      globalFlows: os.Path
  ): Unit =
    for
      tier <- pickTier(ui, "Where should the new flow be saved:", globalFlows)
      goal <- promptDescription(ui, "Describe what the flow should do")
    do
      ShellOutput.info("picking a filename…")
      val target = FlowAuthoring.prepareAutoTarget(
        tier,
        FlowAuthoring.suggestFilenameForGoal(goal),
        workDir,
        globalFlows
      )
      ShellOutput.info(s"filename: ${target.flowPath.last}")
      AuthorAction
        .create(goal, AuthorParams(tier, target), ui, terminal)
        .discard

  /** Fork-an-existing-flow: pick the source flow from every tier (same rows
    * View/Edit use) → tier for the fork's target → mode (ADR 0021 §6/§9
    * amendment) → hand or agent. `private[shell]` so a scripted-UI test can
    * drive it directly.
    */
  private[shell] def createForkFlow(
      ui: ShellUi,
      terminal: Terminal,
      workDir: os.Path = os.pwd,
      globalFlows: os.Path = GlobalSettings.defaultFlows,
      spawnEditor: (Terminal, os.Path) => Int = EditAction.editInPlace
  ): Unit =
    for
      source <- selectFlow(ui, "Fork which flow?")
      tier <- pickTier(ui, "Where should the fork be saved:", globalFlows)
      mode <- pickChangeMode(ui)
    do
      mode match
        case ChangeMode.Hand =>
          forkFlowByHand(
            terminal,
            source,
            tier,
            workDir,
            globalFlows,
            spawnEditor
          )
        case ChangeMode.Agent =>
          forkFlowByAgent(ui, terminal, source, tier, workDir, globalFlows)

  /** Fork+hand (ADR 0021 §9 amendment): copies the source straight to the
    * fork's auto-derived target (same [[FlowAuthoring.forkFilenameDefault]]/
    * [[FlowAuthoring.prepareAutoTarget]] as the agent path), then opens the
    * copy in the editor — no goal/changes prompt, since there's no agent to
    * describe them to. `spawnEditor` injectable like [[editSettings]]'s own
    * seam.
    */
  private def forkFlowByHand(
      terminal: Terminal,
      source: DiscoveredFlow,
      tier: CreateTier,
      workDir: os.Path,
      globalFlows: os.Path,
      spawnEditor: (Terminal, os.Path) => Int
  ): Unit =
    val target = FlowAuthoring.prepareAutoTarget(
      tier,
      FlowAuthoring.forkFilenameDefault(source.name),
      workDir,
      globalFlows
    )
    os.copy(source.path, target.flowPath, createFolders = true)
    spawnEditor(terminal, target.flowPath).discard

  /** Fork+agent: describe the changes, filename auto-derived from the source's
    * name/description and the described changes via
    * [[FlowAuthoring.suggestFilenameForFork]] (uniquified on collision — never
    * prompted for), then hands off to [[AuthorAction.fork]] — same sandboxed
    * flow-based launch as [[createNewFlowByAgent]].
    */
  private def forkFlowByAgent(
      ui: ShellUi,
      terminal: Terminal,
      source: DiscoveredFlow,
      tier: CreateTier,
      workDir: os.Path,
      globalFlows: os.Path
  ): Unit =
    promptDescription(ui, "Describe the changes for the fork").foreach:
      changes =>
        ShellOutput.info("picking a filename…")
        val target = FlowAuthoring.prepareAutoTarget(
          tier,
          FlowAuthoring.suggestFilenameForFork(
            source.name,
            source.description,
            changes
          ),
          workDir,
          globalFlows
        )
        ShellOutput.info(s"filename: ${target.flowPath.last}")
        AuthorAction
          .fork(source, changes, AuthorParams(tier, target), ui, terminal)
          .discard

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

  /** "Clear stack settings (format/lint/test) — re-detected on the next flow
    * run" (ADR 0021 §8/§4): [[StackAction.status]] does the guarded read/parse
    * (a missing file, or one with no stack lines already, is a no-op with a
    * one-line explanation; an unparseable file aborts instead of being
    * surgically edited blind); on a live status
    * [[StackAction.clearIfConfirmed]] renders it, confirms, and calls
    * [[StackAction.clear]] — which strips the stack lines
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
