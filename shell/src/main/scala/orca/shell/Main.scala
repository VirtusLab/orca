package orca.shell

import org.jline.terminal.{Terminal, TerminalBuilder}
import orca.OrcaDir
import orca.agents.BackendTag
import orca.runner.{ManifestSession, RunManifest}
import orca.settings.{AgentSpec, GlobalSettings, SettingsFile, SettingsScope}
import orca.shell.create.{CreateFlow, CreateTarget, CreateTier}
import orca.shell.flows.{
  BuiltInFlows,
  CustomizeTier,
  DiscoveredFlow,
  FlowCatalog,
  FlowEditor,
  FlowOrigin,
  FlowViewer
}
import orca.shell.run.{ChildTerminal, FlowLauncher, LaunchResult}
import orca.shell.sessions.{ManifestReader, ReadRun, ResumeCommand}
import orca.shell.ui.{Choice, ShellUi, UiOutcome}
import orca.shell.wizard.{FirstRun, FirstRunStatus, Wizard}
import orca.subprocess.{PathProbe, QuietProc}
import ox.discard

import scala.annotation.tailrec
import scala.util.Try
import scala.util.control.NonFatal

/** Entry point for the `orca` shell executable (ADR 0021). */
object Main:

  def main(args: Array[String]): Unit =
    // scala-cli's dependency-download progress writes bare `\r` (no line
    // clear) before handing control here, so the cursor can still be mid-line
    // over that stale text; clear it first so the banner starts on a clean
    // line instead of appending to it.
    print("[2K\r")
    println(s"orca shell ${ShellVersion.value}")
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
        println(
          s"orca: the global settings file is malformed — ${error.message}"
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
    warnings.foreach(w => println(s"orca: $w"))
    val continueDisabledReason =
      if runs.nonEmpty then None else Some("no sessions recorded yet")
    ui.select("orca shell", MainMenu.choices(continueDisabledReason)) match
      case UiOutcome.Cancelled               => ()
      case UiOutcome.Selected(MenuItem.Exit) => ()
      case UiOutcome.Selected(MenuItem.Reconfigure) =>
        wizard.run(reconfigure = true).discard
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
        createFlow(ui, terminal)
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
      println(FlowViewer.render(os.read(flow.path), tty))

  /** Opens the chosen flow in `$VISUAL`/`$EDITOR`/`vi`. Project and global
    * flows are edited in place; a built-in is never edited in its cache copy,
    * so [[customizeThenEditPath]] offers to copy it into a tier first. The
    * editor is a tty child like the flow runner, so it runs under the same
    * [[ChildTerminal.withChild]] bracket (ADR 0021 §2).
    */
  private def editFlow(ui: ShellUi, terminal: Terminal): Unit =
    selectFlow(ui, "Edit which flow?").foreach: flow =>
      val path =
        if flow.origin != FlowOrigin.BuiltIn then Some(flow.path)
        else customizeThenEditPath(ui, flow)
      path.foreach: p =>
        ChildTerminal
          .withChild(terminal)(
            FlowEditor.edit(FlowEditor.resolveEditor(sys.env.get), p)
          )
          .discard

  /** Selects a flow, prompts for the task text, then runs it as a tty-inherited
    * child under [[ChildTerminal.withChild]] (ADR 0021 §2). Verbose is not
    * exposed here in v1 — task text only; a later task can add a verbose
    * confirm alongside session tracking.
    */
  private def runFlow(ui: ShellUi, terminal: Terminal): Unit =
    for
      flow <- selectFlow(ui, "Run which flow?")
      task <- promptTask(ui)
    do
      val result = ChildTerminal.withChild(terminal)(
        FlowLauncher.run(ui, flow.path, task, os.pwd)
      )
      println(outcomeLine(result))

  /** Prompts for the flow's task text, re-prompting on blank input — an empty
    * `userPrompt` reaches the flow's agent directly (branch naming, the coding
    * session's instructions), so it's rejected here rather than passed through
    * as a degenerate run.
    */
  @tailrec private def promptTask(ui: ShellUi): Option[String] =
    ui.input("Task for the flow") match
      case UiOutcome.Cancelled => None
      case UiOutcome.Selected(text) if text.trim.isEmpty =>
        println("orca: task text can't be empty")
        promptTask(ui)
      case UiOutcome.Selected(text) => Some(text)

  private def outcomeLine(result: LaunchResult): String = result match
    case LaunchResult.Ok           => "orca: flow finished"
    case LaunchResult.Failed(exit) => s"orca: flow failed (exit code $exit)"
    case LaunchResult.Cancelled    => "orca: run cancelled"

  /** Create-a-flow (ADR 0021 §9): tier → filename → goal → harness (defaulting
    * to the configured coding agent), then extracts the bundled API material
    * into the harness's workspace and execs it with an initial prompt under
    * [[ChildTerminal.withChild]]. Cancelling any prompt, or a filename
    * collision (reported by [[CreateFlow.prepareTarget]]), aborts back to the
    * menu without launching anything.
    */
  private def createFlow(ui: ShellUi, terminal: Terminal): Unit =
    val workDir = os.pwd
    val globalFlows = GlobalSettings.defaultFlows
    for
      tier <- selectCreateTier(ui, globalFlows)
      target <- promptFlowTarget(ui, tier, workDir, globalFlows)
      goal <- promptGoal(ui)
      backend <- selectHarness(ui)
    do runCreateFlowHarness(ui, terminal, tier, target, workDir, goal, backend)

  private def selectCreateTier(
      ui: ShellUi,
      globalFlows: os.Path
  ): Option[CreateTier] =
    ui.select(
      "Create a flow — where should it be saved?",
      List(
        Choice(CreateTier.Project, "Project (.orca/flows/)"),
        Choice(CreateTier.Global, s"Global ($globalFlows)")
      )
    ) match
      case UiOutcome.Cancelled      => None
      case UiOutcome.Selected(tier) => Some(tier)

  /** Prompts for the flow's filename and resolves it to a target path via
    * [[CreateFlow.prepareTarget]], printing and returning `None` on a collision
    * — the harness writes the flow file itself, so an existing file at the
    * target path is never overwritten.
    */
  private def promptFlowTarget(
      ui: ShellUi,
      tier: CreateTier,
      workDir: os.Path,
      globalFlows: os.Path
  ): Option[CreateTarget] =
    ui.input("Flow filename") match
      case UiOutcome.Cancelled => None
      case UiOutcome.Selected(rawName) =>
        CreateFlow.prepareTarget(tier, rawName, workDir, globalFlows) match
          case Left(message) =>
            println(s"orca: $message")
            None
          case Right(target) => Some(target)

  /** Prompts for the flow's goal, re-prompting on blank input — mirrors
    * [[promptTask]]'s rationale: an empty goal would reach the harness as a
    * degenerate initial prompt.
    */
  @tailrec private def promptGoal(ui: ShellUi): Option[String] =
    ui.input("Goal for the new flow") match
      case UiOutcome.Cancelled => None
      case UiOutcome.Selected(text) if text.trim.isEmpty =>
        println("orca: goal can't be empty")
        promptGoal(ui)
      case UiOutcome.Selected(text) => Some(text)

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
      "Harness for the authoring session",
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

  /** Extracts the API material into the tier's cache dir (project:
    * `.orca/cache/`; global: `cache/` under the config-home orca dir), builds
    * the initial prompt, and execs the harness from `target.cwd` under
    * [[ChildTerminal.withChild]] (ADR 0021 §2). When [[CreateFlow.harnessArgv]]
    * returns a paste-fallback prompt (opencode), it's printed and the user must
    * confirm they've read it before the harness launches — its TUI switches to
    * the alternate screen buffer, which would otherwise wipe the print before
    * anyone could copy it. Reports whether `target.flowPath` exists once the
    * harness session ends, with the `scala-cli compile` hint either way. The
    * exec itself is wrapped in a `NonFatal` backstop, same as
    * [[resumeSession]]'s — a missing harness binary otherwise throws
    * `IOException` out of `os.proc`, which `check = false` doesn't cover since
    * that only governs a non-zero exit, not a failed process start.
    */
  private def runCreateFlowHarness(
      ui: ShellUi,
      terminal: Terminal,
      tier: CreateTier,
      target: CreateTarget,
      workDir: os.Path,
      goal: String,
      backend: BackendTag
  ): Unit =
    val version = ShellVersion.value
    val cacheBase = tier match
      case CreateTier.Project => OrcaDir.ensureCache(workDir)
      case CreateTier.Global =>
        val cache = target.cwd / "cache"
        os.makeDir.all(cache)
        cache
    val apiDir = CreateFlow.extractApiMaterial(cacheBase, version)
    val prompt =
      CreateFlow.initialPrompt(goal, target.flowPath, apiDir, version)
    val launch = CreateFlow.harnessArgv(backend, prompt)
    val ready = launch.pastePrompt match
      case None => true
      case Some(toPaste) =>
        println(
          s"orca: paste this prompt into the agent once it opens:\n\n$toPaste\n"
        )
        ui.confirm("Ready to launch?", default = true) match
          case UiOutcome.Selected(proceed) => proceed
          case UiOutcome.Cancelled         => false
    if !ready then println("orca: create-flow cancelled")
    else
      try
        val exitCode = ChildTerminal.withChild(terminal):
          os.proc(launch.argv)
            .call(
              cwd = target.cwd,
              stdin = os.Inherit,
              stdout = os.Inherit,
              stderr = os.Inherit,
              check = false
            )
            .exitCode
        println(s"orca: harness session ended (exit code $exitCode)")
        if os.exists(target.flowPath) then
          println(
            s"orca: ${target.flowPath} created — verify with `scala-cli compile ${target.flowPath}`"
          )
        else println(s"orca: ${target.flowPath} was not created")
      catch
        case NonFatal(e) =>
          println(s"orca: create-flow launch failed — ${e.getMessage}")

  /** One prior run's manifest paired with the session the user picked to resume
    * — everything [[resumeSession]] needs (the harness command comes from the
    * session; the working directory comes from the manifest, which may differ
    * from `os.pwd`).
    */
  private[shell] case class SessionSelection(
      manifest: RunManifest,
      session: ManifestSession
  )

  /** Prompts among every session across `runs` (already newest-run-first from
    * [[ManifestReader.list]]) and resumes the chosen one. A cancelled prompt,
    * or `runs` being empty (unreachable via the menu today, since the item is
    * disabled then, but harmless), is a silent no-op.
    */
  private def continueSession(
      ui: ShellUi,
      terminal: Terminal,
      runs: List[ReadRun]
  ): Unit =
    ui.select("Continue which session?", sessionRows(runs)) match
      case UiOutcome.Cancelled           => ()
      case UiOutcome.Selected(selection) => resumeSession(terminal, selection)

  /** One row per session across every run, in `runs`' order (newest-run-first;
    * a run's own sessions keep the manifest's order). Disabling here previews
    * only what [[ResumeCommand.staticGate]] can tell without a live harness
    * call: a wireId-less session (pi always) or an unrecognised harness.
    * Gemini's real resumability needs `gemini --list-sessions` (deferred to
    * selection, in [[resumeSession]]) — `staticGate` passes any gemini session
    * with a wireId, leaving its row enabled pending that later check, per the
    * ADR's example UX (research 08 §C — only pi is greyed out up front).
    */
  private[shell] def sessionRows(
      runs: List[ReadRun]
  ): List[Choice[SessionSelection]] =
    runs.flatMap: run =>
      run.manifest.sessions.map: session =>
        val label = sessionLabel(session, run.crashed)
        val disabledReason = ResumeCommand.staticGate(session).left.toOption
        Choice(
          SessionSelection(run.manifest, session),
          label,
          disabledReason = disabledReason
        )

  /** `<agent> (<role>) — stage <stage> [<harness>]` (ADR 0021 §8), omitting the
    * role/stage segments when absent; a crashed run's sessions get a trailing
    * `(crashed)` marker on every row (simpler than a separate per-run heading,
    * and just as visible in a flat session list).
    */
  private def sessionLabel(session: ManifestSession, crashed: Boolean): String =
    val role = session.role.fold("")(r => s" ($r)")
    val stage = session.stage.fold("")(s => s" — stage $s")
    val harness = harnessSettingsName(session.harness)
    val crashedSuffix = if crashed then " (crashed)" else ""
    s"${session.agent}$role$stage [$harness]$crashedSuffix"

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

  /** Parses the manifest's stored `workDir` and confirms it's still a directory
    * — a checkout deleted after its run finished otherwise crashes resume:
    * `os.Path` throws `IllegalArgumentException` on a relative or malformed
    * string, and `os.proc`'s `cwd` throws `IOException` on a well-formed but
    * now-missing directory. Both collapse to the one message here rather than
    * propagating past the menu.
    */
  private[shell] def validatedWorkDir(raw: String): Either[String, os.Path] =
    val resolved =
      try Some(os.Path(raw)).filter(p => Try(os.isDir(p)).getOrElse(false))
      catch case NonFatal(_) => None
    resolved.toRight(
      s"the recorded working directory $raw no longer exists"
    )

  /** Resolves gemini's index (a live `gemini --list-sessions` from the
    * manifest's `workDir`, matching the session's stored wireId) if needed,
    * then execs the resume command as a tty-inherited child under
    * [[ChildTerminal.withChild]] (ADR 0021 §2) from that same `workDir` — which
    * may differ from `os.pwd` (claude/gemini/opencode scope session lookup by
    * cwd). A failed or missing `gemini` binary is treated the same as "index
    * not found": [[ResumeCommand.build]] reports it as not resumable.
    * [[validatedWorkDir]] guards the manifest's `workDir` up front; the exec
    * itself is further wrapped in a `NonFatal` backstop (e.g. the checkout
    * vanishing in the gap between that check and this exec, or the resume
    * binary itself being missing).
    */
  private def resumeSession(
      terminal: Terminal,
      selection: SessionSelection
  ): Unit =
    validatedWorkDir(selection.manifest.workDir) match
      case Left(reason) => println(s"orca: can't resume — $reason")
      case Right(workDir) =>
        val isGemini =
          BackendTag
            .fromWireName(selection.session.harness)
            .contains(BackendTag.Gemini)
        val geminiIndex =
          if !isGemini then None
          else
            selection.session.wireId.flatMap: uuid =>
              try
                val listing = QuietProc
                  .call(Seq("gemini", "--list-sessions"), cwd = workDir)
                ResumeCommand.geminiIndexOf(listing.out.text(), uuid)
              catch case NonFatal(_) => None
        ResumeCommand.build(selection.session, geminiIndex) match
          case Left(reason) => println(s"orca: can't resume — $reason")
          case Right(argv) =>
            try
              val exitCode = ChildTerminal.withChild(terminal):
                os.proc(argv)
                  .call(
                    cwd = workDir,
                    stdin = os.Inherit,
                    stdout = os.Inherit,
                    stderr = os.Inherit,
                    check = false
                  )
                  .exitCode
              println(s"orca: session ended (exit code $exitCode)")
            catch
              case NonFatal(e) =>
                println(s"orca: resume failed — ${e.getMessage}")

  /** Prompts for Project or Global, copies the built-in there via
    * [[FlowEditor.customizeTarget]], and returns the copy's path — `None` on
    * Cancelled or on a name collision (reported and left unedited).
    */
  private def customizeThenEditPath(
      ui: ShellUi,
      flow: DiscoveredFlow
  ): Option[os.Path] =
    val globalFlows = GlobalSettings.defaultFlows
    val tierChoices = List(
      Choice(CustomizeTier.Project, "Project (.orca/flows/)"),
      Choice(CustomizeTier.Global, s"Global ($globalFlows)")
    )
    ui.select(
      s"'${flow.name}' is built-in — customize it into",
      tierChoices
    ) match
      case UiOutcome.Cancelled => None
      case UiOutcome.Selected(tier) =>
        FlowEditor.customizeTarget(flow, tier, os.pwd, globalFlows) match
          case Left(message) =>
            println(s"orca: $message")
            None
          case Right(path) => Some(path)

  /** Lists flows across the three tiers, guarding the project tier's component
    * chain against a committed symlink first (`.orca` or `.orca/flows`
    * redirecting reads/writes outside the tree) — a read-only check
    * ([[OrcaDir.assertNoOrcaSymlinks]]), so listing never creates `.orca`. Both
    * that guard and the listing itself (built-in extraction can hit a full-disk
    * or permission error) are wrapped here: any failure is reported and the
    * caller gets `None`, same as Cancelled, so the menu redraws instead of the
    * shell crashing.
    */
  private def selectFlow(ui: ShellUi, title: String): Option[DiscoveredFlow] =
    val workDir = os.pwd
    val flows =
      try
        OrcaDir.assertNoOrcaSymlinks(workDir, OrcaDir.flowsPath(workDir))
        Some(
          FlowCatalog.list(
            OrcaDir.flowsPath(workDir),
            GlobalSettings.defaultFlows,
            BuiltInFlows.extracted(sys.env.get, os.home, ShellVersion.value)
          )
        )
      catch
        case NonFatal(e) =>
          println(s"orca: couldn't list flows — ${e.getMessage}")
          None
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
