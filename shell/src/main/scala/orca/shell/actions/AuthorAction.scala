package orca.shell.actions

import org.jline.terminal.Terminal
import orca.OrcaDir
import orca.agents.BackendTag
import orca.shell.ShellVersion
import orca.shell.create.{FlowAuthoring, CreateTarget, CreateTier}
import orca.shell.flows.DiscoveredFlow
import orca.shell.run.ChildTerminal
import orca.shell.ui.{ShellOutput, ShellUi, UiOutcome}

import scala.util.control.NonFatal

/** Where the new/forked flow is saved and who authors it (ADR 0021 §9) — the
  * already-resolved parameters `Main.createNewFlow`/`createForkFlow` gather via
  * prompts before calling into [[AuthorAction]].
  */
private[shell] case class AuthorParams(
    tier: CreateTier,
    target: CreateTarget,
    backend: BackendTag,
    yolo: Boolean
)

/** Outcome of [[AuthorAction.create]]/[[AuthorAction.fork]]: either the harness
  * process ran to completion with `exit`, or it was never launched at all — the
  * opencode paste-confirm was declined, or the exec itself failed to even start
  * ([[scala.util.control.NonFatal]], e.g. a missing binary).
  */
private[shell] enum AuthorOutcome:
  case Launched(exit: Int)
  case NotLaunched

/** Authors a new or forked flow with a harness's help (ADR 0021 §9): extracts
  * the bundled API material, builds the initial prompt, and launches the
  * authoring session. The prompting that produces `goal`/`changes`/`params`
  * lives in `Main.createNewFlow`/`createForkFlow`.
  */
private[shell] object AuthorAction:

  /** New-flow authoring: extracts the bundled API material into the tier's
    * cache dir, builds [[FlowAuthoring.initialPrompt]], and launches the
    * authoring session.
    */
  def create(
      goal: String,
      params: AuthorParams,
      workDir: os.Path,
      ui: ShellUi,
      terminal: Terminal
  ): AuthorOutcome =
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
    launchAuthoringSession(
      ui,
      terminal,
      params.target,
      prompt,
      params.backend,
      params.yolo
    )

  /** Fork-an-existing-flow authoring: extracts the
    * bundled API material, resolves the source flow to a harness-readable path
    * ([[FlowAuthoring.resolveForkSource]]), builds [[FlowAuthoring.forkPrompt]], and
    * launches the authoring session.
    */
  def fork(
      source: DiscoveredFlow,
      changes: String,
      params: AuthorParams,
      workDir: os.Path,
      ui: ShellUi,
      terminal: Terminal
  ): AuthorOutcome =
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
    launchAuthoringSession(
      ui,
      terminal,
      params.target,
      prompt,
      params.backend,
      params.yolo
    )

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

  /** Execs the harness from `target.cwd` with `prompt` as its initial message
    * under [[ChildTerminal.withChild]] (ADR 0021 §2) — the shared final step
    * for both [[create]] and [[fork]]. Prints [[FlowAuthoring.yoloCaveat]] first
    * when set (pi/opencode can't honor `yolo` via argv). When
    * [[FlowAuthoring.harnessArgv]] returns a paste-fallback prompt (opencode),
    * it's printed and the user must confirm they've read it before the harness
    * launches — its TUI switches to the alternate screen buffer, which would
    * otherwise wipe the print before anyone could copy it. Reports whether
    * `target.flowPath` exists once the harness session ends, with the
    * `scala-cli compile` hint either way. The exec itself is wrapped in a
    * `NonFatal` backstop — a missing harness binary otherwise throws
    * `IOException` out of `os.proc`, which `check = false` doesn't cover since
    * that only governs a non-zero exit, not a failed process start.
    */
  private def launchAuthoringSession(
      ui: ShellUi,
      terminal: Terminal,
      target: CreateTarget,
      prompt: String,
      backend: BackendTag,
      yolo: Boolean
  ): AuthorOutcome =
    val launch = FlowAuthoring.harnessArgv(backend, prompt, yolo)
    FlowAuthoring.yoloCaveat(backend, yolo).foreach(ShellOutput.info)
    val ready = launch.pastePrompt match
      case None => true
      case Some(toPaste) =>
        ShellOutput.info(
          s"paste this prompt into the agent once it opens:\n\n$toPaste\n"
        )
        ui.confirm("Ready to launch?", default = true) match
          case UiOutcome.Selected(proceed) => proceed
          case UiOutcome.Cancelled         => false
    if !ready then
      ShellOutput.info("create-flow cancelled")
      AuthorOutcome.NotLaunched
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
        ShellOutput.info(s"harness session ended (exit code $exitCode)")
        if os.exists(target.flowPath) then
          ShellOutput.info(
            s"${target.flowPath} created — verify with `scala-cli compile ${target.flowPath}`"
          )
        else ShellOutput.info(s"${target.flowPath} was not created")
        AuthorOutcome.Launched(exitCode)
      catch
        case NonFatal(e) =>
          ShellOutput.error(s"create-flow launch failed — ${e.getMessage}")
          AuthorOutcome.NotLaunched
