package orca.shell.actions

import org.jline.terminal.Terminal
import orca.OrcaDir
import orca.shell.ShellVersion
import orca.shell.create.{
  AuthoringSandbox,
  CreateTarget,
  CreateTier,
  FlowAuthoring
}
import orca.shell.flows.{BuiltInFlows, DiscoveredFlow}
import orca.shell.run.{FallbackPolicy, FlowFlags, FlowLauncher, LaunchResult}
import orca.shell.ui.{ShellOutput, ShellUi}

/** Where the new/forked flow is saved (ADR 0021 §9) — the already-resolved
  * parameters `Main.createNewFlow`/`createForkFlow` gather before calling into
  * [[AuthorAction]].
  */
private[shell] case class AuthorParams(tier: CreateTier, target: CreateTarget)

/** Authors a new or forked flow by running the built-in
  * `implement-interactive.sc` flow with an authoring task as its prompt (ADR
  * 0021 §9): the configured planning/coding/review agents — and their model
  * pins — do the writing, exactly as they would for any other flow run. The run
  * happens inside a throwaway [[AuthoringSandbox]], never the user's
  * repository: the flow writes the file at the sandbox root, and on success
  * [[AuthorAction]] copies it out to the real tier. The prompting that produces
  * `goal`/`changes`/`params` lives in `Main.createNewFlow`/`createForkFlow`.
  */
private[shell] object AuthorAction:

  /** The built-in flow every authoring session runs — resolved straight from
    * the built-in tier ([[BuiltInFlows.extracted]]), bypassing project/global
    * precedence: authoring always uses orca's own copy, never a same-named flow
    * a project or the global tier happens to define.
    */
  private val AuthoringFlowName = "implement-interactive.sc"

  /** [[FlowLauncher.runAnnounced]]'s exact shape — a type alias so `create`/
    * `fork` can take it as an injectable parameter, defaulting to the real
    * thing, the same way [[FlowAuthoring.suggestFilename]]'s `runner` stands in
    * for a real subprocess in tests.
    */
  private[shell] type FlowLaunch =
    (
        FallbackPolicy,
        os.Path,
        String,
        os.Path,
        FlowFlags,
        Terminal
    ) => LaunchResult

  /** New-flow authoring: sets up the sandbox, extracts the bundled API material
    * into its cache, builds [[FlowAuthoring.initialPrompt]] against the
    * sandbox-local target, and runs it as the authoring flow's task.
    */
  def create(
      goal: String,
      params: AuthorParams,
      ui: ShellUi,
      terminal: Terminal,
      launch: FlowLaunch = FlowLauncher.runAnnounced
  ): LaunchResult =
    val sandbox = AuthoringSandbox.create()
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

  /** Fork-an-existing-flow authoring: sets up the sandbox, copies the source
    * flow beside the extracted API material
    * ([[FlowAuthoring.resolveForkSource]] — nothing is ever inside the sandbox,
    * so the copy branch always runs), builds [[FlowAuthoring.forkPrompt]]
    * against the sandbox-local target, and runs it as the authoring flow's
    * task.
    */
  def fork(
      source: DiscoveredFlow,
      changes: String,
      params: AuthorParams,
      ui: ShellUi,
      terminal: Terminal,
      launch: FlowLaunch = FlowLauncher.runAnnounced
  ): LaunchResult =
    val sandbox = AuthoringSandbox.create()
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
    val prompt = FlowAuthoring.forkPrompt(
      changes,
      sourcePath,
      sandboxTarget(sandbox, params),
      apiDir,
      ShellVersion.value
    )
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
      launch: FlowLaunch
  ): LaunchResult =
    val flow =
      BuiltInFlows.extracted(sys.env.get, os.home, ShellVersion.value) /
        AuthoringFlowName
    val result = launch(
      FallbackPolicy.Ask(ui),
      flow,
      prompt,
      sandbox,
      FlowFlags(verbose = false, skipBranch = false),
      terminal
    )
    finishAuthoring(result, sandbox, params)
    result

  /** Copies the authored file out of the sandbox and disposes of it: on
    * [[LaunchResult.Ok]] with the file present, the copy lands at the real
    * tier's target (already reserved collision-free) and the sandbox is
    * deleted; Ok with the file missing is reported as an error. A failed run
    * keeps the sandbox — with a notice — so the partial work is inspectable; a
    * cancelled one is cleaned up silently.
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
        else if os.exists(target) then
          // Keep the sandbox: it holds the only copy of the authored flow.
          ShellOutput.error(
            s"$target appeared during the authoring run — the flow is at $authored"
          )
        else
          os.copy(authored, target, createFolders = true)
          ShellOutput.info(s"flow created at $target")
          AuthoringSandbox.delete(sandbox)
      case LaunchResult.Failed(_) =>
        ShellOutput.info(
          s"authoring workspace kept at $sandbox for inspection"
        )
      case LaunchResult.Cancelled =>
        AuthoringSandbox.delete(sandbox)
