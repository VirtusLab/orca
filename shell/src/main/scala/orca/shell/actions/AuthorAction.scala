package orca.shell.actions

import org.jline.terminal.Terminal
import orca.{OrcaArgs, OrcaDir, RunTarget, Uncommitted}
import orca.shell.{ShellEnv, ShellVersion}
import orca.shell.create.{
  AuthoringSandbox,
  FlowAuthoring,
  FlowCommit,
  FlowDestination
}
import orca.shell.flows.DiscoveredFlow
import orca.shell.run.{FallbackPolicy, FlowLauncher, LaunchResult, LaunchedFlow}
import orca.shell.ui.{ShellOutput, ShellUi}

/** Authors a new, forked or edited flow by running the built-in `simple.sc`
  * flow with an authoring task as its prompt (ADR 0021 §9): the configured
  * coding/review agents — and their model pins — do the writing, exactly as
  * they would for any other flow run. No planning stage: the task
  * (copy-and-modify, or write from a goal) is small and well-scoped enough that
  * splitting it into a plan first is pure overhead. The run happens inside a
  * throwaway [[AuthoringSandbox]], never the user's repository: the flow writes
  * the file at the sandbox root, and on success [[AuthorAction]] copies it out
  * to the real tier. `launch` is [[FlowLauncher.runAnnounced]] in production.
  */
private[shell] object AuthorAction:

  /** The built-in flow every authoring session runs — resolved straight from
    * the built-in tier ([[BuiltInFlows.extracted]]), bypassing project/global
    * precedence: authoring always uses orca's own copy, never a same-named flow
    * a project or the global tier happens to define.
    */
  private val AuthoringFlowName = "simple.sc"

  /** A new file (create/fork) must not exist at the destination; an edit
    * overwrites the flow it edits.
    */
  private enum Outcome:
    case New, Edit

  /** New-flow authoring: builds [[FlowAuthoring.initialPrompt]] against the
    * sandbox-local target and runs it as the authoring flow's task.
    */
  def create(
      goal: String,
      destination: FlowDestination,
      ui: ShellUi,
      terminal: Terminal,
      launch: FlowLauncher.FlowLaunch
  )(using ShellEnv): LaunchResult =
    val sandbox = AuthoringSandbox.create(destination.flowPath.last)
    val apiDir = extractApiMaterial(sandbox)
    val prompt = FlowAuthoring.initialPrompt(
      goal,
      sandboxTarget(sandbox, destination),
      apiDir,
      ShellVersion.value
    )
    launchAuthoringFlow(
      prompt,
      sandbox,
      Outcome.New,
      destination,
      ui,
      terminal,
      launch
    )

  /** Fork-an-existing-flow authoring: copies `source` beside the API material
    * ([[FlowAuthoring.copyForkSource]]) and runs [[FlowAuthoring.forkPrompt]]
    * as the authoring flow's task. `destination` must not exist yet.
    */
  def fork(
      source: DiscoveredFlow,
      changes: String,
      destination: FlowDestination,
      ui: ShellUi,
      terminal: Terminal,
      launch: FlowLauncher.FlowLaunch
  )(using ShellEnv): LaunchResult =
    authorFrom(source, changes, Outcome.New, destination, ui, terminal, launch)

  /** Edit-by-agent: like [[fork]] with [[FlowAuthoring.editPrompt]], and the
    * result overwrites `flow` itself — `flow.path` is `destination.flowPath`.
    */
  def edit(
      flow: DiscoveredFlow,
      changes: String,
      destination: FlowDestination,
      ui: ShellUi,
      terminal: Terminal,
      launch: FlowLauncher.FlowLaunch
  )(using ShellEnv): LaunchResult =
    authorFrom(flow, changes, Outcome.Edit, destination, ui, terminal, launch)

  private def authorFrom(
      source: DiscoveredFlow,
      changes: String,
      outcome: Outcome,
      destination: FlowDestination,
      ui: ShellUi,
      terminal: Terminal,
      launch: FlowLauncher.FlowLaunch
  )(using ShellEnv): LaunchResult =
    val sandbox = AuthoringSandbox.create(destination.flowPath.last)
    val apiDir = extractApiMaterial(sandbox)
    val sourcePath = FlowAuthoring.copyForkSource(
      source.path,
      source.name,
      apiDir
    )
    val buildPrompt = outcome match
      case Outcome.New  => FlowAuthoring.forkPrompt
      case Outcome.Edit => FlowAuthoring.editPrompt
    val prompt = buildPrompt(
      changes,
      sourcePath,
      sandboxTarget(sandbox, destination),
      apiDir,
      ShellVersion.value
    )
    launchAuthoringFlow(
      prompt,
      sandbox,
      outcome,
      destination,
      ui,
      terminal,
      launch
    )

  private def extractApiMaterial(sandbox: os.Path): os.Path =
    FlowAuthoring.extractApiMaterial(
      OrcaDir.ensureCache(sandbox),
      ShellVersion.value
    )

  /** Where the flow writes the authored file: the sandbox root, under the real
    * target's filename — visible in the sandbox flow's stage commits, copied
    * out by [[finishAuthoring]] on success.
    */
  private def sandboxTarget(
      sandbox: os.Path,
      destination: FlowDestination
  ): os.Path =
    sandbox / destination.flowPath.last

  /** Runs the built-in authoring flow ([[AuthoringFlowName]], resolved from the
    * built-in tier) with `prompt` as its task, via
    * [[FlowLauncher.runAnnounced]] — same launch path, forced-version/fallback
    * semantics, and tty-inherited terminal as "Run a flow" — with the SANDBOX
    * as the working directory, then hands the outcome to [[finishAuthoring]].
    */
  private def launchAuthoringFlow(
      prompt: String,
      sandbox: os.Path,
      outcome: Outcome,
      destination: FlowDestination,
      ui: ShellUi,
      terminal: Terminal,
      launch: FlowLauncher.FlowLaunch
  )(using env: ShellEnv): LaunchResult =
    val flow = env.extractBuiltInFlows() / AuthoringFlowName
    val result = launch(
      FallbackPolicy.Ask(ui),
      LaunchedFlow.file(flow),
      OrcaArgs(
        userPrompt = prompt,
        verbose = false,
        target = RunTarget.NewBranch(Uncommitted.Stash),
        branch = None
      ),
      sandbox,
      terminal
    )
    finishAuthoring(result, sandbox, outcome, destination)
    result

  /** Copies the authored file out of the sandbox and disposes of it: on
    * [[LaunchResult.Ok]] with the file present, the copy lands at the real
    * tier's target and the sandbox is deleted; Ok with the file missing is
    * reported as an error. For [[Outcome.New]] the target must still be absent
    * — reserved collision-free before the run, but re-checked here since the
    * run itself could have raced a same-named write elsewhere; for
    * [[Outcome.Edit]] the target IS the source's own path, so overwriting it is
    * the whole point and the existence check is skipped. A failed run keeps the
    * sandbox — with a notice — so the partial work is inspectable; a cancelled
    * one is cleaned up silently.
    */
  private def finishAuthoring(
      result: LaunchResult,
      sandbox: os.Path,
      outcome: Outcome,
      destination: FlowDestination
  ): Unit =
    result match
      case LaunchResult.Ok =>
        val authored = sandboxTarget(sandbox, destination)
        val target = destination.flowPath
        if !os.exists(authored) then
          ShellOutput.error(
            s"the authoring flow finished, but ${authored.last} was not written"
          )
          AuthoringSandbox.delete(sandbox)
        else if os.exists(target) && outcome == Outcome.New then
          // Keep the sandbox: it holds the only copy of the authored flow.
          ShellOutput.error(
            s"$target appeared during the authoring run — the flow is at $authored"
          )
        else
          os.copy(
            authored,
            target,
            createFolders = true,
            replaceExisting = outcome == Outcome.Edit
          )
          val committed = tryCommit(destination, outcome)
          ShellOutput.info(successNotice(target, outcome, committed))
          AuthoringSandbox.delete(sandbox)
      case LaunchResult.Failed(_) =>
        ShellOutput.info(
          s"authoring workspace kept at $sandbox for inspection"
        )
      case LaunchResult.Cancelled =>
        AuthoringSandbox.delete(sandbox)

  /** Commits a Project destination into its repo (ADR 0021 §9 amendment); a
    * Global one has no repo. [[FlowCommit.commitScoped]] itself declines
    * (without failing anything) when `repo` isn't inside a git work tree or
    * HEAD is unborn.
    */
  private def tryCommit(
      destination: FlowDestination,
      outcome: Outcome
  ): Boolean =
    destination match
      case FlowDestination.Project(flowPath, repo) =>
        FlowCommit.commitScoped(
          flowPath,
          repo,
          commitMessage(flowPath.last, outcome)
        )
      case FlowDestination.Global(_) => false

  private def commitMessage(fileName: String, outcome: Outcome): String =
    val verb = outcome match
      case Outcome.New  => "add"
      case Outcome.Edit => "update"
    s"orca: $verb flow $fileName"

  /** The finishing notice: names what happened to the copied-out file (created
    * vs. updated) and, when [[tryCommit]] didn't commit it, appends a one-line
    * hint rather than silently leaving the user to notice its absence from `git
    * log`.
    */
  private def successNotice(
      target: os.Path,
      outcome: Outcome,
      committed: Boolean
  ): String =
    val verb = outcome match
      case Outcome.New  => "created"
      case Outcome.Edit => "updated"
    if committed then s"flow $verb and committed at $target"
    else s"flow $verb at $target — commit it yourself to track it"
