package orca.runner

import orca.{
  BranchNamingStrategy,
  FlowContext,
  FlowControl,
  InStage,
  OrcaArgs,
  OrcaDir,
  OrcaFlowException,
  RuntimeInStage,
  StackSettings,
  WorkspaceWrite
}
import orca.agents.{BackendTag, Agent, SessionId, WireSessionId}
import orca.events.OrcaEvent
import orca.util.TextUtil
import orca.progress.{
  FeatureBranch,
  ProgressHeader,
  ProgressStore,
  ProtectedBranchRefused,
  RecoveryCheck,
  SessionRecord,
  UnsafeBranchRefRefused
}
import orca.settings.{AgentSettings, SettingsFile, SettingsScope}
import orca.tools.GitTool
import org.slf4j.LoggerFactory
import ox.either.orThrow

import scala.util.control.NonFatal

/** Marker that a flow failure has ALREADY been reported to the user's event
  * surface. Thrown by the `surfaced` brackets after they report `cause`, so
  * `flow()` may discard it without re-reporting. The contract is the whole
  * point of the type: any other `NonFatal` exception escaping `runFlow`
  * unwrapped means a code path that was NOT bracketed — an unsurfaced failure
  * `flow()` prints to stderr as a backstop rather than exiting silently.
  */
private[orca] final case class SurfacedFlowFailure(cause: Throwable)
    extends RuntimeException(cause)

/** Flow setup/teardown/recovery lifecycle (ADR 0018 §2.4/§2.5). Owns the
  * privileged, outside-any-user-stage git and progress-store mutations that
  * bracket the body.
  */
object FlowLifecycle:

  /** The context-bound phases of one run, in mandated order: session
    * rehydration → body → disjoint success/failure teardown (ADR 0018
    * §2.4/§2.5). [[setup]] (branch + log binding) is not a phase here —
    * `runFlow` runs it before constructing the context, so `flowSetup`'s
    * resolved stack settings arrive as a constructor input.
    *
    * Rehydration and the body run inside the `surfaced` bracket, which reports
    * the error, logs, and rethrows a [[SurfacedFlowFailure]] so `flow()` never
    * exits without an explanation; the body phase additionally runs
    * `teardownFailure` on the way out. `teardownSuccess` runs OUTSIDE
    * `surfaced` deliberately: it is already internally best-effort, and a
    * bracket meant for reporting failures would convert a cosmetic teardown
    * failure into a reported, failed successful run.
    *
    * The failure and success teardowns are structurally disjoint — the body
    * catch rethrows, so success teardown is unreachable on a body failure.
    */
  private[orca] def run(
      ctx: DefaultFlowContext[?, ?, ?],
      flowSetup: FlowSetup,
      returnToStartBranch: Boolean,
      debug: Boolean
  )(body: FlowControl ?=> Unit): Unit =
    val log = LoggerFactory.getLogger("orca.flow")
    // Report/log/wrap bracket for every phase that can fail. Reports once
    // (reusing the context's reported-set so it never double-prints a failure
    // a nested stage already surfaced), logs, prints the stack under
    // `--verbose`/debug, then throws `SurfacedFlowFailure`. Carries no teardown
    // side effect — `teardownFailure` is the body phase's job alone (below).
    def surfaced[T](op: => T): T =
      try op
      catch
        case NonFatal(e) =>
          ctx.reportOnce(e)(
            ctx.emit(OrcaEvent.Error(TextUtil.throwableMessage(e)))
          )
          log.debug("flow aborted", e)
          if debug then e.printStackTrace(System.err)
          throw SurfacedFlowFailure(e)
    surfaced(rehydrateSessions(ctx, ctx.codingAgent, ctx.progressStore))
    // The whole flow body runs as a top-level stage: an otherwise unhandled
    // exception surfaces as a single Error event. `teardownFailure` runs only
    // here in the body phase, so a success-teardown error can never trigger
    // `resetHard` or strand the user on the feature branch.
    try surfaced(body(using ctx))
    catch
      case f @ SurfacedFlowFailure(e) =>
        // `e` was already reported by `surfaced`. Discard the failed stage's
        // partial edits; if the reset itself fails, attach it as suppressed so
        // it travels with `e` rather than masking the original failure. Since
        // `e` was already reported before this reset ran, log and print the
        // suppressed failure here too, and emit a user-visible `Step` so the
        // user knows the tree may still hold the failed run's partial edits.
        try teardownFailure(ctx.git)
        catch
          case NonFatal(t) =>
            e.addSuppressed(t)
            log.debug("teardownFailure failed after body failure", t)
            if debug then t.printStackTrace(System.err)
            ctx.emit(
              OrcaEvent.Step(
                "warning: workspace reset failed after the flow failure — " +
                  "the working tree may still contain the failed run's partial edits"
              )
            )
        throw f
    teardownSuccess(ctx.git, flowSetup, returnToStartBranch)

  /** Replay the persisted resume-wire-id map (ADR 0018 §2.6) into each
    * session's own agent's in-memory registry, so a resumed run resumes against
    * the right wire id. For each [[orca.progress.SessionRecord]] carrying a
    * `resumeWireId`, registers it into the agent [[targetAgent]] resolves for
    * the record's `backend` tag. A tag matching no known backend (edited log or
    * renamed [[BackendTag]] case) is skipped loudly via an `OrcaEvent.Step`
    * rather than guessed. `record.id`/`wireId` are equally untrusted
    * (log-sourced): a value that fails to parse is skipped the same loud way.
    */
  private[orca] def rehydrateSessions(
      ctx: FlowContext,
      lead: Agent[?],
      store: ProgressStore
  ): Unit =
    for
      log <- store.load().toList
      record <- log.sessions
      wireId <- record.resumeWireId
    do
      targetAgent(ctx, lead, record.backend) match
        case None =>
          ctx.emit(
            OrcaEvent.Step(
              s"warning: session '${record.name}' #${record.occurrence} " +
                s"recorded backend tag '${record.backend.getOrElse("")}' " +
                "does not match any known backend — skipping rehydration"
            )
          )
        case Some(agent) =>
          register(ctx, agent, record, wireId)

  /** Untagged records go to the lead; a tag matching no accessor (edited log,
    * or a renamed [[BackendTag]] case) is skipped, not guessed.
    */
  private def targetAgent(
      ctx: FlowContext,
      lead: Agent[?],
      tag: Option[String]
  ): Option[Agent[?]] =
    tag match
      case None    => Some(lead)
      case Some(t) => BackendTag.fromWireName(t).map(ctx.agentFor)

  /** Parse `record.id`/`wire` (both log-sourced, untrusted) and register the
    * mapping into `agent`; a value that fails to parse is skipped with a
    * visible warning rather than rehydrated raw.
    */
  private def register[B <: BackendTag](
      ctx: FlowContext,
      agent: Agent[B],
      record: SessionRecord,
      wire: String
  ): Unit =
    (SessionId.parse[B](record.id), WireSessionId.parse[B](wire)) match
      case (Some(id), Some(wireId)) => agent.registerResumeWireId(id, wireId)
      case _ =>
        ctx.emit(
          OrcaEvent.Step(
            s"warning: session '${record.name}' #${record.occurrence} has an " +
              "invalid recorded id or wire id — skipping rehydration"
          )
        )

  /** ADR 0019 migration warning: a repo that gitignores `.orca/` keeps the
    * committed stack settings out of version control, so every run names the
    * likely `.orca/` line to remove. Skipped under a programmatic override
    * (which neither reads nor writes the file). Best-effort and never fails the
    * flow.
    */
  private def warnIfSettingsIgnored(
      git: GitTool,
      stackOverridden: Boolean,
      emit: OrcaEvent => Unit
  ): Unit =
    if !stackOverridden && git.isIgnored(OrcaDir.settingsSubPath) then
      emit(
        OrcaEvent.Step(
          "stack settings at .orca/settings.properties are gitignored — " +
            "remove the '.orca/' line from .gitignore so they can be " +
            "committed (scratch self-ignores under .orca/cache/)"
        )
      )

  /** Outcome of [[setup]]: the resolved progress store, the feature branch the
    * run is bound to, the starting branch to restore on success, and the
    * resolved stack settings (ADR 0019).
    *
    * `featureBranch` is a [[FeatureBranch]], not a bare `String`: both arms of
    * `setup` construct one via [[FeatureBranch.resolve]], so a protected name
    * can never reach this field — "delete/checkout an unvalidated name" is
    * unrepresentable here.
    */
  private[orca] case class FlowSetup(
      store: ProgressStore,
      featureBranch: FeatureBranch,
      startBranch: String,
      stackSettings: StackSettings
  )

  /** Bind the run to a branch + progress log before the body runs (ADR 0018
    * §2.4/§2.5). Records the starting branch, snapshots the log file, stashes a
    * dirty tree, then either resumes an existing log or starts fresh (resolve a
    * branch name, create it, write + commit the header). All git/store
    * mutations run with a runtime-minted `WorkspaceWrite`, and branch-name
    * resolution with a runtime-minted `InStage` — setup is privileged,
    * predating any user stage.
    *
    * The progress header is untrusted input on load (the log is human-visible
    * and pushable), so a resumed run:
    *   - Snapshots the log file before `ensureClean` and restores it if the
    *     stash removed it, so the header is always readable.
    *   - Validates the header before any destructive action (safe refs,
    *     prompt-hash match, no protected feature branch). A
    *     parseable-but-invalid header is a hard abort (`OrcaFlowException`),
    *     not a silent fresh start. (An unparseable log → fresh run, but warned
    *     since it's distinguishable from a genuinely absent log.)
    *   - Cross-checks that the current branch is the one the header records: a
    *     log that surfaced on a branch it does not name (e.g. carried along by
    *     a merge) aborts rather than resuming against the wrong branch.
    *
    * On resume `startBranch` is the header's recorded `startingBranch` (the
    * original branch at first run), so a return-to-start goes to that original
    * branch, not the re-run's current feature branch.
    */
  private[orca] def setup(
      args: OrcaArgs,
      // The resolved coding-role agent (ADR 0020): branch-name resolution and
      // stack discovery — the run's privileged pre-body model calls — run here.
      agent: Agent[?],
      git: GitTool,
      workDir: os.Path,
      branchNaming: Option[BranchNamingStrategy],
      // The stack resolution, parsed by `runFlow` upstream (ADR 0020 §6); a
      // malformed file has already aborted before this point.
      resolution: SettingsResolution,
      // True when `flow(stackSettings = Some(...))` governs the stack commands,
      // gating the gitignored-settings migration warning.
      stackOverridden: Boolean,
      store: ProgressStore,
      emit: OrcaEvent => Unit
  ): FlowSetup =
    given InStage = RuntimeInStage.token()
    given WorkspaceWrite = RuntimeInStage.workspaceToken()
    val log = LoggerFactory.getLogger("orca.flow")
    warnIfSettingsIgnored(git, stackOverridden, emit)
    val startBranch = git.currentBranch()
    // Snapshot the log file before the stash, restore it if the stash
    // removed it — so an uncommitted/untracked log is still readable below.
    val snapshot = snapshotLog(store.path)
    val _ = git.ensureClean("orca: starting flow")
    restoreLogIfMissing(store.path, snapshot)
    // Discovery (ADR 0019) is sequenced after `ensureClean` (whose `stash -u`
    // would stash a just-written untracked file straight back out of the
    // tree). When it runs, the written file gets its own commit
    // (`commitDiscoveredSettings`, below), so no later `add -A` sweep carries
    // it under an unrelated message. `discovered` flags that the write
    // happened, gating those commits. A discovery failure aborts setup as a
    // surfaced failure (no degrade-to-empty-file — see [[StackDiscovery]]).
    val (stackSettings, discovered) = resolution match
      case SettingsResolution.Resolved(settings) => (settings, false)
      case SettingsResolution.NeedsDiscovery(existingContent) =>
        val (settings, entries) = StackDiscovery.discover(agent, workDir, emit)
        // Absent file → write the whole rendered file. An agents-only
        // hand-written file (content captured pre-stash) → append the stack
        // entries below the untouched agent lines, so discovery never clobbers
        // agent keys (ADR 0020 §7). `os.write.over` because the stash may have
        // already swept an untracked file out of the tree.
        val fileText = existingContent match
          case None => SettingsFile.render(entries)
          case Some(content) =>
            content + "\n" + SettingsFile.renderAppend(entries)
        os.write.over(
          OrcaDir.settingsPath(workDir),
          fileText,
          createFolders = true
        )
        emit(
          OrcaEvent.Step(
            "written to .orca/settings.properties — review and edit as needed."
          )
        )
        (settings, true)
    // The protected set both arms enforce: the always-protected floor
    // (`main`/`master`) plus the repo's detected default branch (best-effort;
    // failed detection falls back to just the floor). Computed once so the
    // fresh and resume arms apply the identical policy from the identical set.
    val protectedBranches =
      RecoveryCheck.alwaysProtected ++ git
        .defaultBranch()
        .map(_.toLowerCase(java.util.Locale.ROOT))
    val (featureBranch, effectiveStartBranch) = store.loadDetailed() match
      case ProgressStore.LoadResult.Corrupt(reason) =>
        // The log file exists but didn't parse. Start fresh (no sane way to
        // resume from unparseable data), but loudly — the user may have
        // expected a resume, so this must be distinguishable from a first run.
        // The `emit(Step)` reaches both the terminal renderer and custom
        // Interaction listeners (e.g. Slack); the logger keeps the DEBUG trace.
        log.warn(
          s"progress log at ${store.path} is corrupt ($reason); starting fresh"
        )
        emit(
          OrcaEvent.Step(
            s"progress log at ${store.path} is corrupt ($reason); " +
              "starting fresh — the previous run's stages will re-run"
          )
        )
        val branch = freshRun(
          args,
          agent,
          git,
          workDir,
          branchNaming,
          store,
          startBranch,
          protectedBranches,
          discovered,
          emit
        )
        (branch, startBranch)
      case ProgressStore.LoadResult.Absent =>
        val branch = freshRun(
          args,
          agent,
          git,
          workDir,
          branchNaming,
          store,
          startBranch,
          protectedBranches,
          discovered,
          emit
        )
        (branch, startBranch)
      case ProgressStore.LoadResult.Loaded(progressLog) =>
        val header = progressLog.header
        // Validate the untrusted header before any destructive action, against
        // the same protected set — a tampered header naming e.g. `trunk` as a
        // feature branch is refused too.
        val featureBranch =
          RecoveryCheck.validateHeader(
            header,
            args.userPrompt,
            protectedBranches
          ) match
            case Left(reason) =>
              throw new OrcaFlowException(
                s"refusing to resume: progress log header failed validation ($reason)"
              )
            case Right(featureBranch) => featureBranch
        // Only resume in place. If the log surfaced on a branch it does not
        // name, it was likely carried here by a merge — abort, don't replay.
        val current = git.currentBranch()
        if current != header.branch then
          throw new OrcaFlowException(
            s"progress log for branch '${header.branch}' found while on " +
              s"'$current' — was it merged? aborting rather than resuming " +
              "against the wrong branch"
          )
        // The recorded start branch (where a return-to-start goes) is the
        // original one, not this feature branch. The branch already exists, so
        // a just-discovered settings file gets its dedicated commit right here
        // (ADR 0019) — this arm has no header commit that would sweep it in.
        if discovered then commitDiscoveredSettings(git, workDir)
        (featureBranch, header.startingBranch)
    FlowSetup(store, featureBranch, effectiveStartBranch, stackSettings)

  /** Outcome of the pre-`ensureClean` stack read: either the resolved values,
    * or the marker that auto-discovery must run. `NeedsDiscovery` carries the
    * existing file content (ADR 0020 §7): `None` when the file is absent or
    * blank (write the whole file), `Some(content)` for an agents-only
    * hand-written file (append the stack entries, leaving agent lines
    * untouched). Content is captured pre-stash so a hand-written file the stash
    * sweeps out of a dirty tree is not lost.
    */
  private[runner] enum SettingsResolution:
    case Resolved(settings: StackSettings)
    case NeedsDiscovery(existingContent: Option[String])

  /** The parsed outcome of both settings files (ADR 0020 §6): the stack
    * resolution, plus the project- and user-global agent keys kept separate so
    * `runFlow` can track each role's source for the announcement `Step`.
    */
  private[runner] case class SettingsRead(
      stack: SettingsResolution,
      projectAgents: AgentSettings,
      globalAgents: AgentSettings
  )

  /** Read + parse both settings files once, before any tree mutation (ADR 0020
    * §6). The project file carries both families; the user-global file carries
    * agent keys only. An unreadable or malformed file — project or global — is
    * a hard abort ([[OrcaFlowException]]); the caller sequences this ahead of
    * `ensureClean`, so a malformed file aborts with no stash and no branch
    * mutation, and its content is captured before the stash can sweep it away.
    *
    * A stack override (`flow(stackSettings = Some(...))`) fixes the stack
    * resolution and skips discovery, but the project file is still read and
    * parsed — its agent keys are honoured and a malformed file still aborts.
    * Absent that override, the stack resolution follows the stack-aware
    * discovery trigger (ADR 0020 §7): a present file naming a stack line (per
    * [[SettingsFile.hasStackLines]]) resolves; an absent, blank, or
    * stack-silent file needs discovery.
    */
  private[orca] def readSettings(
      workDir: os.Path,
      globalSettingsPath: os.Path,
      stackOverride: Option[StackSettings]
  ): SettingsRead =
    val projectPath = OrcaDir.settingsPath(workDir)
    // A repo can commit `.orca/settings.properties` — or `.orca` itself — as a
    // symlink pointing outside the tree, which `os.read`/`os.write.over` follow,
    // landing discovery output at the target. `os.isLink` inspects only the
    // final path component, so a symlinked `.orca` directory would slip past a
    // leaf-only check; guard both components here, before any tree mutation,
    // rather than at the post-stash write site. The `.orca` check is
    // defense-in-depth: `OrcaDir.ensureCache` already refuses a symlinked
    // `.orca` earlier, but the discovery write reaches the directory through
    // `createFolders`, not `ensureRoot`.
    abortIfSymlink(OrcaDir.rootPath(workDir))
    abortIfSymlink(projectPath)
    val projectContent: Option[String] =
      if os.exists(projectPath) then Some(readOrAbort(projectPath))
      else None
    val projectParsed =
      projectContent.map(c =>
        parseOrAbort(c, SettingsScope.Project, projectPath)
      )
    val globalAgents: AgentSettings =
      if os.exists(globalSettingsPath) then
        parseOrAbort(
          readOrAbort(globalSettingsPath),
          SettingsScope.UserGlobal,
          globalSettingsPath
        ).agents
      else AgentSettings.empty
    val projectAgents =
      projectParsed.map(_.agents).getOrElse(AgentSettings.empty)
    val stack: SettingsResolution =
      stackOverride match
        case Some(settings) => SettingsResolution.Resolved(settings)
        case None =>
          projectContent match
            case None => SettingsResolution.NeedsDiscovery(None)
            case Some(content) =>
              if SettingsFile.hasStackLines(content) then
                SettingsResolution.Resolved(projectParsed.get.stack)
              else if content.isBlank then
                SettingsResolution.NeedsDiscovery(None)
              else SettingsResolution.NeedsDiscovery(Some(content))
    SettingsRead(stack, projectAgents, globalAgents)

  /** Abort the run if `path` is a symlink, before any read or write decision —
    * this runs ahead of `ensureClean`, so the abort precedes any tree mutation
    * and leaves the current branch untouched. `os.isLink` does not follow the
    * final link, so a dangling link is caught too.
    *
    * Accepted residual (TOCTOU): the check runs at read time and the discovery
    * write happens later, so a purely local race could swap in a symlink in
    * that window. Out of scope under the committed-repo-symlink threat model
    * (the attacker controls repo content, not the local filesystem mid-run).
    */
  private def abortIfSymlink(path: os.Path): Unit =
    if os.isLink(path) then
      throw new OrcaFlowException(
        s"$path is a symlink — refusing to read or write through it (a " +
          "committed symlink could redirect discovery output outside the " +
          "working tree)"
      )

  private def readOrAbort(path: os.Path): String =
    try os.read(path)
    catch
      case NonFatal(e) =>
        throw new OrcaFlowException(
          s"cannot read settings at $path: ${e.getMessage}"
        )

  private def parseOrAbort(
      content: String,
      scope: SettingsScope,
      path: os.Path
  ): orca.settings.ParsedSettings =
    SettingsFile.parse(content, scope) match
      case Right(s) => s
      case Left(err) =>
        throw new OrcaFlowException(
          s"invalid settings at $path: ${err.message}"
        )

  /** Fresh run: resolve + create the branch (returned to the caller), then
    * commit the header so it is the branch's first commit. Shared by the
    * absent-log case and the corrupt-log case (which warns, then falls through
    * to the same fresh start). Needs both tokens: `InStage` because branch-name
    * resolution may call the cheap model, `WorkspaceWrite` for the git
    * checkout/commit and header write.
    *
    * The resolved name is minted into a [[FeatureBranch]] before it reaches
    * git: a name colliding with a protected branch is refused and falls back to
    * a deterministic `flowFallbackName` (same prompt → same fallback, so a
    * resumed run still finds the branch), loudly, rather than aborting —
    * unattended runs must not flip between success and failure because the
    * cheap model's summary phrased itself as "main" this time.
    *
    * [[createFreshBranch]] then applies the same "never silently adopt" policy
    * to a git-level `BranchAlreadyExists` collision: an unrelated pre-existing
    * branch must never be silently checked out and carried into this run.
    */
  private def freshRun(
      args: OrcaArgs,
      agent: Agent[?],
      git: GitTool,
      workDir: os.Path,
      branchNaming: Option[BranchNamingStrategy],
      store: ProgressStore,
      startBranch: String,
      protectedBranches: Set[String],
      discovered: Boolean,
      emit: OrcaEvent => Unit
  )(using InStage, WorkspaceWrite): FeatureBranch =
    val strategy =
      branchNaming.getOrElse(BranchNamingStrategy.shortenPrompt)
    val resolvedName = strategy.resolve(args.userPrompt, agent)
    // Resolved once, shared by both fallback triggers below (a protected-name
    // refusal and a git-level `BranchAlreadyExists` collision use the exact
    // same deterministic name).
    val fallback = resolveFallback(args.userPrompt, protectedBranches)
    val protectionChecked =
      FeatureBranch.resolve(resolvedName, protectedBranches) match
        case Right(featureBranch) => featureBranch
        case Left(ProtectedBranchRefused(name)) =>
          emit(
            OrcaEvent.Step(
              s"branch name '$name' is protected — using '${fallback.value}' instead"
            )
          )
          fallback
        case Left(UnsafeBranchRefRefused(name)) =>
          // Unreachable: `strategy.resolve` always returns an already-slugged
          // name, so `resolve`'s shape check can never refuse it. Guarded
          // defensively rather than assumed.
          throw new OrcaFlowException(
            s"internal error: strategy-resolved branch name '$name' is not " +
              "a safe ref"
          )
    val branch = createFreshBranch(git, protectionChecked, fallback, emit)
    // A just-discovered settings file gets its own commit here — after the
    // branch exists, before the header commit below — so the header commit
    // carries only the progress log its message names (ADR 0019).
    if discovered then commitDiscoveredSettings(git, workDir)
    store.writeHeader(
      ProgressHeader(
        startingBranch = startBranch,
        branch = branch.value,
        promptHash = ProgressStore.hashPrompt(args.userPrompt)
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")
    branch

  /** Give the just-discovered settings file its own commit (ADR 0019), so the
    * commit that follows carries only what its message names. Called on both
    * lifecycle arms whenever discovery actually wrote the file.
    *
    * [[GitTool.commitOnly]]'s pathspec guarantees the commit carries exactly
    * this one path — anything else dirty or untracked stays out. Not
    * `forceAdd`: a repo that still ignores `.orca/` must keep the file ignored
    * (the migration warning already covers it), so the commit is skipped when
    * [[GitTool.isIgnored]] reports the path excluded. Only the progress log
    * punches through the ignore, for resume correctness.
    */
  private def commitDiscoveredSettings(git: GitTool, workDir: os.Path)(using
      WorkspaceWrite
  ): Unit =
    if !git.isIgnored(OrcaDir.settingsSubPath) then
      git.commitOnly(
        OrcaDir.settingsPath(workDir),
        "orca: stack settings (discovered)"
      )

  /** The deterministic `flow-<hash>` fallback name for `userPrompt`, minted
    * into a [[FeatureBranch]]. Shared by both places `freshRun` needs a
    * guaranteed-safe rename, so both triggers agree on (and compare against)
    * the exact same name. Re-validated against the protected set defensively
    * rather than assumed safe.
    */
  private def resolveFallback(
      userPrompt: String,
      protectedBranches: Set[String]
  ): FeatureBranch =
    val fallbackName = BranchNamingStrategy.flowFallbackName(userPrompt)
    FeatureBranch.resolve(fallbackName, protectedBranches) match
      case Right(featureBranch) => featureBranch
      case Left(_) =>
        throw new OrcaFlowException(
          s"internal error: deterministic fallback branch name " +
            s"'$fallbackName' is itself a protected branch"
        )

  /** Create `candidate` fresh via `git.createBranch`, never silently adopting
    * an existing branch: a `Left(BranchAlreadyExists)` means a branch by that
    * name is already there for some unrelated reason (an earlier run's
    * leftover, a manual branch, a slug collision), and carrying its prior
    * history into this run with zero signal is the hazard this closes.
    *
    * Applies the same fallback-rename policy `freshRun` uses for a
    * protected-name refusal: retry once, loudly, with `fallback`. If
    * `candidate` is already `fallback`, retrying would recreate the identical
    * name and collide again — abort immediately instead. Either way, a
    * deterministic name colliding on a fresh run means a previous run's branch
    * is still there; the user must decide what to do with it.
    */
  private def createFreshBranch(
      git: GitTool,
      candidate: FeatureBranch,
      fallback: FeatureBranch,
      emit: OrcaEvent => Unit
  )(using WorkspaceWrite): FeatureBranch =
    def doubleCollisionAbort(name: String): Nothing =
      throw new OrcaFlowException(
        s"branch '$name' already exists — this deterministic name collided " +
          "on a fresh run, which means a previous run's branch is still " +
          "around; delete it or use a different prompt before retrying"
      )
    git.createBranch(candidate.value) match
      case Right(()) => candidate
      case Left(_) =>
        if fallback.value == candidate.value then
          doubleCollisionAbort(fallback.value)
        else
          emit(
            OrcaEvent.Step(
              s"branch '${candidate.value}' already exists — using " +
                s"'${fallback.value}' instead"
            )
          )
          git.createBranch(fallback.value) match
            case Right(()) => fallback
            case Left(_)   => doubleCollisionAbort(fallback.value)

  /** Read the bytes of the progress-log file if it exists, else `None`. */
  private[runner] def snapshotLog(path: os.Path): Option[Array[Byte]] =
    if os.exists(path) then Some(os.read.bytes(path)) else None

  /** Restore the progress-log file from a pre-stash snapshot if the stash
    * removed it, so the header is always readable. A no-op when there was
    * nothing to snapshot or the file still exists.
    */
  private[runner] def restoreLogIfMissing(
      path: os.Path,
      snapshot: Option[Array[Byte]]
  ): Unit =
    snapshot.foreach: bytes =>
      if !os.exists(path) then os.write.over(path, bytes, createFolders = true)

  /** Run a teardownSuccess leg best-effort: any `NonFatal` failure is caught
    * and debug-logged (never printed, never surfaced) so it cannot escape
    * teardown, trigger the failure path, or strand the user. `what` names the
    * leg for the log line.
    */
  private def bestEffort(what: String)(op: => Unit): Unit =
    val log = LoggerFactory.getLogger("orca.flow")
    try op
    catch
      case NonFatal(e) =>
        log.debug(s"teardownSuccess: $what failed (cosmetic, swallowed)", e)

  /** Successful teardown (ADR 0018 §2.5): remove the progress-log file in a
    * final commit so a merged branch is clean, then hand off to
    * [[finishBranch]] for where HEAD lands.
    *
    * Errors during log removal, the cleanup commit, or the branch handoff are
    * cosmetic on an already-successful run — every leg runs through
    * [[bestEffort]]. A missing progress-log file (the ordinary "already
    * removed" case) stays fully silent; every other failure is debug-logged.
    */
  private[orca] def teardownSuccess(
      git: GitTool,
      setup: FlowSetup,
      returnToStartBranch: Boolean
  ): Unit =
    // Teardown runs outside any user stage, so it mints its own
    // `WorkspaceWrite`. No LLM call happens here, so `InStage` isn't needed.
    given WorkspaceWrite = RuntimeInStage.workspaceToken()
    try
      bestEffort("remove progress log"):
        try
          val _ = os.remove(setup.store.path)
        catch case _: java.nio.file.NoSuchFileException => ()
      // `add -A` in commit picks up the removal; NothingToCommit means it was
      // never committed — harmless. A genuine commit failure is cosmetic: the
      // run already succeeded and the progress file is gone from the tree.
      bestEffort("commit progress-log removal"):
        val _ = git.commit("orca: remove progress log")
    finally
      bestEffort("branch handoff"):
        finishBranch(git, setup, returnToStartBranch)

  /** Where HEAD ends up after a successful run. A throwaway feature branch
    * (only orca bookkeeping, no user code vs the start branch) is deleted and
    * HEAD returns to the starting branch. Otherwise the feature branch is kept,
    * and `returnToStartBranch` chooses where HEAD lands — stay on the feature
    * branch (the default) or return to the starting branch (PR flows).
    * Best-effort and success-path-only; never deletes start/protected branches.
    */
  private def finishBranch(
      git: GitTool,
      setup: FlowSetup,
      returnToStartBranch: Boolean
  )(using WorkspaceWrite): Unit =
    val throwaway =
      setup.featureBranch.value != setup.startBranch &&
        git
          .diffBranchExcludingOrca(setup.startBranch, setup.featureBranch.value)
          .isBlank
    if throwaway then
      // The start branch existed when this run began, so a plain `checkout`
      // suffices; if it's gone mid-run that's genuinely exceptional, not a case
      // to paper over by creating it anew.
      git.checkout(setup.startBranch).orThrow
      git.deleteBranch(setup.featureBranch.value)
    else if returnToStartBranch then git.checkout(setup.startBranch).orThrow
    // else: stay on the feature branch (the default).

  /** Failure teardown (ADR 0018 §2.5): discard the failed stage's uncommitted
    * partial edits with `git reset --hard` (which restores the last committed
    * log), staying on the feature branch so the next run resumes in place.
    */
  private[orca] def teardownFailure(git: GitTool): Unit =
    given WorkspaceWrite = RuntimeInStage.workspaceToken()
    git.resetHard()
