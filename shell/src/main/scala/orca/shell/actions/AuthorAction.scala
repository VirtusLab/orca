package orca.shell.actions

import org.jline.terminal.Terminal
import orca.OrcaDir
import orca.shell.ShellVersion
import orca.shell.create.{CreateTarget, CreateTier, FlowAuthoring}
import orca.shell.flows.{BuiltInFlows, DiscoveredFlow}
import orca.shell.run.{FallbackPolicy, FlowFlags, FlowLauncher, LaunchResult}
import orca.shell.ui.ShellUi

/** Where the new/forked flow is saved (ADR 0021 §9) — the already-resolved
  * parameters `Main.createNewFlow`/`createForkFlow` gather via prompts before
  * calling into [[AuthorAction]].
  */
private[shell] case class AuthorParams(tier: CreateTier, target: CreateTarget)

/** Authors a new or forked flow by running the built-in
  * `implement-interactive.sc` flow with an authoring task as its prompt (ADR
  * 0021 §9): the configured planning/coding/review agents — and their model
  * pins — do the writing, exactly as they would for any other flow run, so
  * there's no separate harness/model/yolo choice here. Extracts the bundled API
  * material, builds the authoring prompt (`FlowAuthoring.initialPrompt`/
  * `forkPrompt`), and launches the flow via [[FlowLauncher.runAnnounced]] — the
  * same forced-version/fallback path `Main.runFlow`/[[RunAction]] use. The
  * prompting that produces `goal`/`changes`/`params` lives in
  * `Main.createNewFlow`/`createForkFlow`.
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

  /** New-flow authoring: extracts the bundled API material into the tier's
    * cache dir, builds [[FlowAuthoring.initialPrompt]], and runs it as the
    * authoring flow's task.
    */
  def create(
      goal: String,
      params: AuthorParams,
      workDir: os.Path,
      ui: ShellUi,
      terminal: Terminal,
      launch: FlowLaunch = FlowLauncher.runAnnounced
  ): LaunchResult =
    val apiDir = FlowAuthoring.extractApiMaterial(
      cacheBaseFor(params.tier, workDir, params.target),
      ShellVersion.value
    )
    val prompt = FlowAuthoring.initialPrompt(
      goal,
      params.target.flowPath,
      apiDir,
      ShellVersion.value
    )
    launchAuthoringFlow(prompt, workDir, ui, terminal, launch)

  /** Fork-an-existing-flow authoring: extracts the bundled API material,
    * resolves the source flow to a readable path
    * ([[FlowAuthoring.resolveForkSource]]), builds
    * [[FlowAuthoring.forkPrompt]], and runs it as the authoring flow's task.
    */
  def fork(
      source: DiscoveredFlow,
      changes: String,
      params: AuthorParams,
      workDir: os.Path,
      ui: ShellUi,
      terminal: Terminal,
      launch: FlowLaunch = FlowLauncher.runAnnounced
  ): LaunchResult =
    val apiDir = FlowAuthoring.extractApiMaterial(
      cacheBaseFor(params.tier, workDir, params.target),
      ShellVersion.value
    )
    val sourcePath = FlowAuthoring.resolveForkSource(
      source.path,
      source.name,
      params.target.cwd,
      apiDir
    )
    val prompt = FlowAuthoring.forkPrompt(
      changes,
      sourcePath,
      params.target.flowPath,
      apiDir,
      ShellVersion.value
    )
    launchAuthoringFlow(prompt, workDir, ui, terminal, launch)

  /** The tier's cache dir to extract the API material into: project flows under
    * `.orca/cache/`, global ones under `cache/` alongside the config-home
    * `orca/` dir (ADR 0021 §9) — shared by [[create]] and [[fork]].
    */
  private def cacheBaseFor(
      tier: CreateTier,
      workDir: os.Path,
      target: CreateTarget
  ): os.Path =
    tier match
      case CreateTier.Project => OrcaDir.ensureCache(workDir)
      case CreateTier.Global =>
        val cache = target.cwd / "cache"
        os.makeDir.all(cache)
        cache

  /** Runs the built-in authoring flow ([[AuthoringFlowName]], resolved from the
    * built-in tier) with `prompt` as its task, via
    * [[FlowLauncher.runAnnounced]] — same launch path, forced-version/fallback
    * semantics, and tty-inherited terminal as "Run a flow". Normal branch
    * semantics apply: a project-tier target lands inside the flow's own branch
    * commits; a global-tier target is written outside the repo, so the branch
    * carries only orca's own bookkeeping and is cleaned up by `FlowLifecycle`'s
    * existing throwaway-branch auto-delete.
    */
  private def launchAuthoringFlow(
      prompt: String,
      workDir: os.Path,
      ui: ShellUi,
      terminal: Terminal,
      launch: FlowLaunch
  ): LaunchResult =
    val flow =
      BuiltInFlows.extracted(sys.env.get, os.home, ShellVersion.value) /
        AuthoringFlowName
    launch(
      FallbackPolicy.Ask(ui),
      flow,
      prompt,
      workDir,
      FlowFlags(verbose = false, skipBranch = false),
      terminal
    )
