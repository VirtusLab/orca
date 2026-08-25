package orca.shell.actions

import org.jline.terminal.Terminal
import orca.OrcaDir
import orca.shell.ShellVersion
import orca.shell.create.{
  AuthoringSandbox,
  CreateTarget,
  CreateTier,
  FlowAuthoring,
  FlowCommit
}
import orca.shell.flows.{BuiltInFlows, DiscoveredFlow}
import orca.shell.run.{FallbackPolicy, FlowFlags, FlowLauncher, LaunchResult}
import orca.shell.ui.{ShellOutput, ShellUi}

/** Where the new/forked/edited flow is saved (ADR 0021 §9) — the
  * already-resolved parameters `Main.createNewFlow`/`createForkFlow`/
  * `editFlow` gather before calling into [[AuthorAction]]. `overwrite` is false
  * for create/fork (the target must NOT already exist — a filename collision is
  * refused); edit-by-agent sets it true and points `target` at the source's own
  * path, so the same finished run legitimately overwrites it instead of being
  * refused for "already existing".
  */
private[shell] case class AuthorParams(
    tier: CreateTier,
    target: CreateTarget,
    overwrite: Boolean = false
)

/** Authors a new or forked flow by running the built-in `simple.sc` flow with
  * an authoring task as its prompt (ADR 0021 §9): the configured coding/review
  * agents — and their model pins — do the writing, exactly as they would for
  * any other flow run. No planning stage: the task (copy-and-modify, or write
  * from a goal) is small and well-scoped enough that splitting it into a plan
  * first is pure overhead. The run happens inside a throwaway
  * [[AuthoringSandbox]], never the user's repository: the flow writes the file
  * at the sandbox root, and on success [[AuthorAction]] copies it out to the
  * real tier. The prompting that produces `goal`/`changes`/`params` lives in
  * `Main.createNewFlow`/`createForkFlow`.
  */
private[shell] object AuthorAction:

  /** The built-in flow every authoring session runs — resolved straight from
    * the built-in tier ([[BuiltInFlows.extracted]]), bypassing project/global
    * precedence: authoring always uses orca's own copy, never a same-named flow
    * a project or the global tier happens to define.
    */
  private val AuthoringFlowName = "simple.sc"

  /** New-flow authoring: sets up the sandbox, extracts the bundled API material
    * into its cache, builds [[FlowAuthoring.initialPrompt]] against the
    * sandbox-local target, and runs it as the authoring flow's task.
    */
  def create(
      goal: String,
      params: AuthorParams,
      ui: ShellUi,
      terminal: Terminal,
      launch: FlowLauncher.FlowLaunch = FlowLauncher.runAnnounced
  ): LaunchResult =
    val sandbox = AuthoringSandbox.create(params.target.flowPath.last)
    val apiDir = FlowAuthoring.extractApiMaterial(
      OrcaDir.ensureCache(sandbox),
      ShellVersion.value
    )
    val prompt = FlowAuthoring.initialPrompt(
      goal,
      sandboxTarget(sandbox, params),
      apiDir,
      ShellVersion.value
    )
    launchAuthoringFlow(prompt, sandbox, params, ui, terminal, launch)

  /** Fork-an-existing-flow authoring (also the edit-by-agent path, `params.
    * overwrite`): sets up the sandbox, copies the source flow beside the
    * extracted API material ([[FlowAuthoring.resolveForkSource]] — nothing is
    * ever inside the sandbox, so the copy branch always runs), builds the
    * authoring task against the sandbox-local target —
    * [[FlowAuthoring.editPrompt]] when `params.overwrite` (worded as an edit,
    * since that's what the user asked for), [[FlowAuthoring.forkPrompt]]
    * otherwise — and runs it as the authoring flow's task.
    */
  def fork(
      source: DiscoveredFlow,
      changes: String,
      params: AuthorParams,
      ui: ShellUi,
      terminal: Terminal,
      launch: FlowLauncher.FlowLaunch = FlowLauncher.runAnnounced
  ): LaunchResult =
    val sandbox = AuthoringSandbox.create(params.target.flowPath.last)
    val apiDir = FlowAuthoring.extractApiMaterial(
      OrcaDir.ensureCache(sandbox),
      ShellVersion.value
    )
    val sourcePath = FlowAuthoring.resolveForkSource(
      source.path,
      source.name,
      sandbox,
      apiDir
    )
    val target = sandboxTarget(sandbox, params)
    val buildPrompt =
      if params.overwrite then FlowAuthoring.editPrompt
      else FlowAuthoring.forkPrompt
    val prompt =
      buildPrompt(changes, sourcePath, target, apiDir, ShellVersion.value)
    launchAuthoringFlow(prompt, sandbox, params, ui, terminal, launch)

  /** Where the flow writes the authored file: the sandbox root, under the real
    * target's filename — visible in the sandbox flow's stage commits, copied
    * out by [[finishAuthoring]] on success.
    */
  private def sandboxTarget(sandbox: os.Path, params: AuthorParams): os.Path =
    sandbox / params.target.flowPath.last

  /** Runs the built-in authoring flow ([[AuthoringFlowName]], resolved from the
    * built-in tier) with `prompt` as its task, via
    * [[FlowLauncher.runAnnounced]] — same launch path, forced-version/fallback
    * semantics, and tty-inherited terminal as "Run a flow" — with the SANDBOX
    * as the working directory, then hands the outcome to [[finishAuthoring]].
    */
  private def launchAuthoringFlow(
      prompt: String,
      sandbox: os.Path,
      params: AuthorParams,
      ui: ShellUi,
      terminal: Terminal,
      launch: FlowLauncher.FlowLaunch
  ): LaunchResult =
    val flow =
      BuiltInFlows.extracted(sys.env.get, os.home, ShellVersion.value) /
        AuthoringFlowName
    val result = launch(
      FallbackPolicy.Ask(ui),
      flow,
      prompt,
      sandbox,
      FlowFlags(verbose = false, skipBranch = false, keepChanges = false),
      terminal
    )
    finishAuthoring(result, sandbox, params)
    result

  /** Copies the authored file out of the sandbox and disposes of it: on
    * [[LaunchResult.Ok]] with the file present, the copy lands at the real
    * tier's target and the sandbox is deleted; Ok with the file missing is
    * reported as an error. For create/fork (`params.overwrite` false) the
    * target must still be absent — reserved collision-free before the run, but
    * re-checked here since the run itself could have raced a same-named write
    * elsewhere; for edit-by-agent (`overwrite` true) the target IS the source's
    * own path, so overwriting it is the whole point and the existence check is
    * skipped. A failed run keeps the sandbox — with a notice — so the partial
    * work is inspectable; a cancelled one is cleaned up silently.
    */
  private def finishAuthoring(
      result: LaunchResult,
      sandbox: os.Path,
      params: AuthorParams
  ): Unit =
    result match
      case LaunchResult.Ok =>
        val authored = sandboxTarget(sandbox, params)
        val target = params.target.flowPath
        if !os.exists(authored) then
          ShellOutput.error(
            s"the authoring flow finished, but ${authored.last} was not written"
          )
          AuthoringSandbox.delete(sandbox)
        else if os.exists(target) && !params.overwrite then
          // Keep the sandbox: it holds the only copy of the authored flow.
          ShellOutput.error(
            s"$target appeared during the authoring run — the flow is at $authored"
          )
        else
          os.copy(
            authored,
            target,
            createFolders = true,
            replaceExisting = params.overwrite
          )
          val committed = tryCommit(target, params)
          ShellOutput.info(successNotice(target, params.overwrite, committed))
          AuthoringSandbox.delete(sandbox)
      case LaunchResult.Failed(_) =>
        ShellOutput.info(
          s"authoring workspace kept at $sandbox for inspection"
        )
      case LaunchResult.Cancelled =>
        AuthoringSandbox.delete(sandbox)

  /** Commits `target` into the Project tier's repo (ADR 0021 §9 amendment) —
    * skipped outright for the Global tier, which has no repo to commit into.
    * [[FlowCommit.commitScoped]] itself declines (without failing anything)
    * when `params.target.cwd` isn't inside a git work tree or HEAD is unborn,
    * so this stays a plain tier gate.
    */
  private def tryCommit(target: os.Path, params: AuthorParams): Boolean =
    params.tier == CreateTier.Project &&
      FlowCommit.commitScoped(
        target,
        params.target.cwd,
        commitMessage(target.last, params.overwrite)
      )

  private def commitMessage(fileName: String, overwrite: Boolean): String =
    val verb = if overwrite then "update" else "add"
    s"orca: $verb flow $fileName"

  /** The finishing notice: names what happened to the copied-out file (created
    * vs. updated) and, when [[tryCommit]] didn't commit it, appends a one-line
    * hint rather than silently leaving the user to notice its absence from `git
    * log`.
    */
  private def successNotice(
      target: os.Path,
      overwrite: Boolean,
      committed: Boolean
  ): String =
    val verb = if overwrite then "updated" else "created"
    if committed then s"flow $verb and committed at $target"
    else s"flow $verb at $target — commit it yourself to track it"
