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
  BranchMode,
  FeatureBranch,
  ProgressHeader,
  ProgressScan,
  ProgressStore,
  ProtectedBranchRefused,
  RecoveryCheck,
  ScannedProgressLog,
  SessionRecord,
  UnsafeBranchRefRefused
}
import orca.settings.{AgentSettings, SettingsFile, SettingsScope}
import orca.tools.GitTool
import org.slf4j.LoggerFactory
import ox.either.orThrow

import scala.util.control.NonFatal

/** Marker that a failure was already reported to the event surface (thrown by
  * `surfaced` after reporting), so `flow()` discards it without re-reporting.
  * Any OTHER `NonFatal` escaping `runFlow` means an un-bracketed code path;
  * `flow()` prints it to stderr as a backstop.
  */
private[orca] final case class SurfacedFlowFailure(cause: Throwable)
    extends RuntimeException(cause)

/** Flow setup/teardown/recovery lifecycle (ADR 0018 §2.4/§2.5). Owns the
  * privileged, outside-any-user-stage git and progress-store mutations that
  * bracket the body.
  */
object FlowLifecycle:

  /** One run's phases, in mandated order: session rehydration → body → disjoint
    * success/failure teardown (ADR 0018 §2.4/§2.5). [[setup]] (branch + log
    * binding) already ran in `runFlow` before the context was built, so its
    * resolved settings arrive here as a constructor input, not a phase.
    *
    * Rehydration and the body run inside `surfaced`, which reports, logs, and
    * rethrows [[SurfacedFlowFailure]]; the body phase also runs
    * `teardownFailure` on the way out. `teardownSuccess` runs OUTSIDE
    * `surfaced` — it's already best-effort, and wrapping it would turn a
    * cosmetic teardown failure into a reported failure on a successful run.
    * Since the body's catch rethrows, success teardown is unreachable after a
    * body failure — the two teardowns are structurally disjoint.
    */
  private[orca] def run(
      ctx: DefaultFlowContext[?, ?, ?],
      flowSetup: FlowSetup,
      returnToStartBranch: Boolean,
      debug: Boolean
  )(body: FlowControl ?=> Unit): Unit =
    val log = LoggerFactory.getLogger("orca.flow")
    // `ctx.reportOnce` dedups against a nested stage that already surfaced this
    // failure. `teardownFailure` is NOT called here — it's the body phase's job
    // alone (below).
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
        // `e` was already reported by `surfaced`. If the reset itself fails, attach
        // it as suppressed (rather than replacing `e`), and log/print it too.
        //
        // This Step names WHY the reset is about to discard changes — `resetHard`
        // itself also emits its own "Discarded uncommitted changes" Step, which
        // alone would read as unexplained data loss.
        ctx.emit(
          OrcaEvent.Step(
            "recovering from the failure — discarding uncommitted changes " +
              "so a re-run resumes from the last completed stage"
          )
        )
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

  /** Outcome of [[setup]] (ADR 0019 stack settings included).
    *
    * `featureBranch` is a [[FeatureBranch]], not a bare `String`: both arms of
    * `setup` construct it via [[FeatureBranch.resolve]], so a protected name
    * can never reach this field — an unvalidated delete/checkout target is
    * unrepresentable here.
    *
    * `branchMode` (mirrors [[ProgressHeader.branchMode]]) gates
    * [[finishBranch]]'s throwaway auto-delete: `Reused` blocks it, since orca
    * bound to a pre-existing branch rather than minting one.
    */
  private[orca] case class FlowSetup(
      store: ProgressStore,
      featureBranch: FeatureBranch,
      startBranch: String,
      stackSettings: StackSettings,
      branchMode: BranchMode
  )

  /** The branch half of [[FlowSetup]] (FP2), resolved by whichever of
    * [[setup]]'s three `store.loadDetailed()` arms runs (corrupt log, absent
    * log, or resumed) before the stack-settings half is folded in.
    */
  private[orca] case class BranchBinding(
      featureBranch: FeatureBranch,
      startBranch: String,
      branchMode: BranchMode
  )

  /** Bind the run to a branch + progress log before the body runs (ADR 0018
    * §2.4/§2.5). Records the starting branch, snapshots the log file, applies
    * the cleanliness policy below, then either resumes an existing log or
    * starts fresh (resolve a branch name, create it, write + commit the
    * header). All git/store mutations run with a runtime-minted
    * `WorkspaceWrite`, and branch-name resolution with a runtime-minted
    * `InStage` — setup is privileged, predating any user stage.
    *
    * Before any of that — and before any tree mutation — a fresh run refuses to
    * start on a branch another run's progress log already claims; see
    * [[abortIfBranchBusy]].
    *
    * Cleanliness policy (ADR 0018 amendment): only skip-branch mode needs the
    * fresh-vs-resume distinction to decide whether to stash, so only there is
    * `store.loadDetailed()` peeked at pre-stash (a throwaway read, gating the
    * branch below only — not reused for the actual routing decision). A FRESH
    * skip-branch run tolerates a dirty tree outright — no stash, no refusal,
    * one informational `Step` naming the file count — since the leftover files
    * are likely the very hand-off context the flow is meant to act on. Every
    * other case (resume, or normal branch-creating mode either way)
    * auto-stashes as before: a resumed run's interrupted stage may have left
    * uncommitted partial work that must not leak into the stage that re-runs.
    * Normal mode's stash decision needs no peek, so it is unchanged.
    *
    * [[abortIfBranchBusy]] also reads the own log pre-stash, in every mode. A
    * pre-stash read can see a dirty working copy, which is why neither peek
    * routes fresh-vs-resume — that stays with the post-stash authoritative read
    * below. The busy check only ever refuses, and a dirty own log reads at
    * worst as `Corrupt`, which it treats like `Absent`: no resumable run of
    * mine here, so another run's claim on this branch still stands.
    *
    * The progress header is untrusted input on load (the log is human-visible
    * and pushable), so the AUTHORITATIVE read — at the `binding` match below —
    * always runs after the cleanliness decision (stash or tolerate), never
    * before: a tracked-but-dirty log must be classified from its last COMMITTED
    * content, never a possibly-broken in-progress edit, in every mode. On top
    * of that, a resumed run:
    *   - Snapshots the log file before the cleanliness decision and restores it
    *     if a stash removed it, so the header is always readable.
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
      // `ORCA_FLOW_NAME`, threaded down from `flow()` rather than read here —
      // stamped into a freshly-written header (`freshRun`) so the shell's
      // "Resume interrupted run" offer (ADR 0021 §3 amendment) knows which
      // flow script to relaunch. `None` for a run started outside the shell.
      flowName: Option[String] = None,
      emit: OrcaEvent => Unit
  ): FlowSetup =
    given InStage = RuntimeInStage.token()
    given WorkspaceWrite = RuntimeInStage.workspaceToken()
    warnIfSettingsIgnored(git, stackOverridden, emit)
    abortIfNoCommits(git)
    val startBranch = git.currentBranch()
    abortIfBranchBusy(store, workDir, startBranch)
    // Snapshot the log file before any stash, restore it after if the stash
    // removed it — so an uncommitted/untracked log is still readable below.
    val snapshot = snapshotLog(store.path)
    val session =
      SetupSession(
        args,
        agent,
        git,
        workDir,
        branchNaming,
        store,
        flowName,
        emit
      )
    session.applyCleanlinessPolicy()
    restoreLogIfMissing(store.path, snapshot)
    // Discovery (ADR 0019) is sequenced after the cleanliness decision (whose
    // stash, when it runs, would sweep a just-written untracked file straight
    // back out of the tree). When it runs, the written file gets its own commit
    // (`commitDiscoveredSettings`, below), so no later `add -A` sweep carries
    // it under an unrelated message. `discovered` flags that the write
    // happened, gating those commits.
    val (stackSettings, discovered) =
      resolveStackSettings(agent, workDir, resolution, emit)
    // The protected set both binding arms enforce: the always-protected floor
    // (`main`/`master`) plus the repo's detected default branch (best-effort;
    // failed detection falls back to just the floor). Computed once so the
    // fresh and resume arms apply the identical policy from the identical set.
    val protectedBranches =
      RecoveryCheck.alwaysProtected ++ git
        .defaultBranch()
        .map(_.toLowerCase(java.util.Locale.ROOT))
    val binding = session.bindBranch(startBranch, protectedBranches, discovered)
    FlowSetup(
      store,
      binding.featureBranch,
      binding.startBranch,
      stackSettings,
      binding.branchMode
    )

  /** Refuse to start a NEW run on a branch that another run's progress log
    * already claims (ADR 0018 §2.5, R1 amendment).
    *
    * Logs are prompt-keyed, so a differently worded task finds this run's own
    * log `Absent` and would otherwise start fresh on whatever branch is checked
    * out — silently sharing it with an interrupted run whose stages are
    * half-done. A log naming the current branch IS such a run: failure teardown
    * keeps the log and stays on the branch, success teardown deletes it.
    *
    * Skipped when this run's own log loads: that is a legitimate resume of the
    * same prompt, and the branch its header names is its own (validated
    * downstream by `bindBranch`) — so other logs naming the branch don't turn a
    * resume into a conflict.
    *
    * Runs before the cleanliness policy, so an abort leaves the tree and branch
    * exactly as the user left them. Reading foreign logs pre-stash can pick up
    * uncommitted content, which is fine here: this check only ever refuses,
    * unlike `bindBranch`'s authoritative post-stash read.
    */
  /** On an unborn HEAD (`git init`, no commits) every later git call that names
    * `HEAD` — starting with `currentBranch()` on the next line — exits 128 with
    * git's "ambiguous argument 'HEAD'" fatal, so refuse here instead.
    *
    * `headCommit()` is also `None` when git itself cannot run, which would get
    * this message rather than its own; accepted, since every path past this
    * point fails on that same broken git anyway.
    */
  private def abortIfNoCommits(git: GitTool): Unit =
    if git.headCommit().isEmpty then
      throw new OrcaFlowException(
        "the repository has no commits yet — make an initial commit " +
          "(`git commit`) before running a flow"
      )

  private def abortIfBranchBusy(
      store: ProgressStore,
      workDir: os.Path,
      startBranch: String
  ): Unit =
    val ownLogLoads =
      store.loadDetailed().isInstanceOf[ProgressStore.LoadResult.Loaded]
    if !ownLogLoads then
      busyBranchLog(store.path, workDir, startBranch).foreach: log =>
        throw new OrcaFlowException(
          branchBusyMessage(log, workDir, startBranch)
        )

  /** The newest by mtime of the OTHER progress logs naming `startBranch` —
    * newest-wins like the shell's resume offer, since several logs (different
    * prompts) can name one branch. Corrupt logs are already dropped by the
    * scan; a scan failure (`.orca` unreadable, or removed mid-listing) yields
    * `None` rather than propagating, since this guard-rail must not fail a run
    * that is otherwise fine.
    */
  private def busyBranchLog(
      ownPath: os.Path,
      workDir: os.Path,
      startBranch: String
  ): Option[ScannedProgressLog] =
    try
      ProgressScan
        .progressLogs(workDir)
        .filter(l => l.path != ownPath && l.header.branch == startBranch)
        .maxByOption(l => os.mtime(l.path))
    catch case NonFatal(_) => None

  /** Identifies the run being refused — its recorded task and flow, plus the
    * log's own path, which is both how the user reads the full task text back
    * and what they delete to abandon the run — and lists every way out.
    *
    * The task is header content, committed and hand-editable, so it reaches the
    * terminal through [[TextUtil.onelinePreview]]: sanitized, and clipped so a
    * long task can't bury the guidance after it.
    *
    * The shell's menu row is mentioned as a possibility, not a promise, and
    * only when the header carries both a flow name and the task text — the
    * shell needs both to offer the row at all, but applies further conditions
    * of its own (`ResumeDetector`), so the always-available re-run route is
    * given either way. Abandoning is spelled as a git removal: the log is
    * committed on the branch, so a plain `rm` is undone by the next run's
    * auto-stash restore.
    */
  private def branchBusyMessage(
      log: ScannedProgressLog,
      workDir: os.Path,
      startBranch: String
  ): String =
    val header = log.header
    val task = header.userPrompt
      .map(TextUtil.onelinePreview(_, 60))
      .getOrElse("(not recorded)")
    val flow = header.flowName
      .map(name => s", flow: ${TextUtil.onelinePreview(name, 40)}")
      .getOrElse("")
    val logPath = log.path.relativeTo(workDir)
    val shellRoute =
      if header.flowName.isDefined && header.userPrompt.isDefined then
        ", which the orca shell may also offer as \"Resume interrupted run\""
      else ""
    s"branch '$startBranch' already has an unfinished orca run on it " +
      s"(task: $task$flow, log: $logPath) — resume it by re-running its flow " +
      s"with the identical task text$shellRoute, abandon it by removing its " +
      s"log (git rm $logPath && git commit -m \"abandon orca run\"), or " +
      "switch to a different branch before starting a new run"

  /** [[setup]]'s fixed inputs — the coding-role agent, git, workDir, the
    * progress store, the emit sink — shared by the cleanliness-policy and
    * branch-binding steps below, so each reads as `session.xxx(...)` rather
    * than repeating the same handful of parameters at every call site.
    */
  private final class SetupSession(
      args: OrcaArgs,
      agent: Agent[?],
      git: GitTool,
      workDir: os.Path,
      branchNaming: Option[BranchNamingStrategy],
      store: ProgressStore,
      flowName: Option[String],
      emit: OrcaEvent => Unit
  ):

    /** Cleanliness policy (ADR 0018 amendment) — see [[setup]]'s doc for why
      * only this mode peeks the store pre-stash. Skip-branch + fresh: tolerate
      * a dirty tree (no stash, one informational `Step` naming the file count)
      * — the leftover files are likely the flow's own hand-off context. Every
      * other case (resume, or normal mode either way) auto-stashes, since an
      * interrupted stage's uncommitted work must not leak into the re-run.
      */
    def applyCleanlinessPolicy()(using WorkspaceWrite): Unit =
      if args.skipBranch.value then
        val isResume =
          store.loadDetailed().isInstanceOf[ProgressStore.LoadResult.Loaded]
        if isResume then
          val _ = git.ensureClean("orca: starting flow")
        else
          val dirty = git.dirtyPaths()
          if dirty.nonEmpty then
            emit(
              OrcaEvent.Step(
                s"leaving ${dirty.size} uncommitted/untracked file(s) in place for the flow"
              )
            )
      else
        val _ = git.ensureClean("orca: starting flow")

    /** Bind the run to a branch + progress log — resume onto the header's
      * branch for a valid log, warn and start fresh from a corrupt one, or
      * start fresh when none exists. This is the AUTHORITATIVE
      * `store.loadDetailed()` read; see [[setup]]'s doc for why it always runs
      * after the cleanliness decision.
      */
    def bindBranch(
        startBranch: String,
        protectedBranches: Set[String],
        discovered: Boolean
    )(using InStage, WorkspaceWrite): BranchBinding =
      store.loadDetailed() match
        case ProgressStore.LoadResult.Corrupt(reason) =>
          warnCorruptLog(reason)
          freshBinding(startBranch, protectedBranches, discovered)
        case ProgressStore.LoadResult.Absent =>
          freshBinding(startBranch, protectedBranches, discovered)
        case ProgressStore.LoadResult.Loaded(progressLog) =>
          resumeBinding(progressLog.header, protectedBranches, discovered)

    /** The log file exists but didn't parse. No sane way to resume from
      * unparseable data, so this warns loudly — distinguishing a corrupt log
      * from a genuinely absent one — before [[bindBranch]] falls through to the
      * same fresh start its absent arm takes. The `emit(Step)` reaches both the
      * terminal renderer and custom Interaction listeners (e.g. Slack); the
      * logger keeps the DEBUG trace.
      */
    private def warnCorruptLog(reason: String): Unit =
      val log = LoggerFactory.getLogger("orca.flow")
      log.warn(
        s"progress log at ${store.path} is corrupt ($reason); starting fresh"
      )
      emit(
        OrcaEvent.Step(
          s"progress log at ${store.path} is corrupt ($reason); " +
            "starting fresh — the previous run's stages will re-run"
        )
      )

    /** Shared by [[bindBranch]]'s corrupt-log and absent-log arms: resolve +
      * create a fresh branch via [[freshRun]], then mint [[BranchMode]] from
      * `args.skipBranch` (skip-branch mode never creates a branch, so it reads
      * as `Reused`).
      */
    private def freshBinding(
        startBranch: String,
        protectedBranches: Set[String],
        discovered: Boolean
    )(using InStage, WorkspaceWrite): BranchBinding =
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
        flowName,
        emit
      )
      BranchBinding(
        branch,
        startBranch,
        if args.skipBranch.value then BranchMode.Reused
        else BranchMode.Created
      )

    /** Resume onto the header's existing branch. Validates the untrusted header
      * against the protected set before any destructive action — invalid is a
      * hard abort, not a silent fresh start — and checks the current branch
      * matches the one the header names (R30): a log surfaced on the wrong
      * branch (e.g. carried by a merge) aborts rather than resuming there.
      * Returns the header's recorded `startingBranch` (the ORIGINAL branch),
      * not this run's pre-cleanliness `startBranch`, so return-to-start lands
      * correctly. A just-discovered settings file gets its own commit here (ADR
      * 0019): this arm creates no branch, so nothing else would commit it.
      */
    private def resumeBinding(
        header: ProgressHeader,
        protectedBranches: Set[String],
        discovered: Boolean
    )(using WorkspaceWrite): BranchBinding =
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
      val current = git.currentBranch()
      if current != header.branch then
        throw new OrcaFlowException(
          s"progress log for branch '${header.branch}' found while on " +
            s"'$current' — was it merged? aborting rather than resuming " +
            "against the wrong branch"
        )
      if discovered then commitDiscoveredSettings(git, workDir)
      BranchBinding(featureBranch, header.startingBranch, header.branchMode)

  /** Resolve the stack settings for the run (ADR 0019 §7): pass through
    * `resolution`'s already-resolved settings, or run [[StackDiscovery]] and
    * write the settings file when discovery is needed — the whole rendered file
    * for an absent one, or the discovered entries appended below an agents-only
    * hand-written file's untouched agent lines (ADR 0020 §7). `os.write.over`
    * because a stash upstream may already have swept an untracked file out of
    * the tree. The returned `discovered` flag is `true` only when this call
    * wrote the file, gating its dedicated commit later in [[setup]]. A
    * discovery failure aborts as a surfaced failure — no degrade-to-empty-file
    * (see [[StackDiscovery]]).
    */
  private def resolveStackSettings(
      agent: Agent[?],
      workDir: os.Path,
      resolution: SettingsResolution,
      emit: OrcaEvent => Unit
  )(using InStage): (StackSettings, Boolean) =
    resolution match
      case SettingsResolution.Resolved(settings) => (settings, false)
      case SettingsResolution.NeedsDiscovery(existingContent) =>
        val (settings, entries) =
          StackDiscovery.discover(agent, workDir, emit, existingContent)
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

  /** Fresh run: resolve + create the branch, then commit the header as the
    * branch's first commit. The commit is pathspec-scoped to just the
    * progress-log file (never `add -A`), so a dirty tree left by skip-branch
    * mode reaches the branch only via the first stage's own commit. Shared by
    * the absent-log and corrupt-log arms of [[bindBranch]]. Needs `InStage`
    * (branch-name resolution may call the cheap model) and `WorkspaceWrite`
    * (the git writes).
    *
    * The resolved name is minted into a [[FeatureBranch]] before reaching git:
    * a protected-name collision falls back to a deterministic
    * `flowFallbackName` (same prompt → same fallback, so a resumed run still
    * finds the branch) rather than aborting — an unattended run must not flip
    * between success and failure because the cheap model phrased its summary as
    * "main" this time. [[createFreshBranch]] applies the same policy to a
    * git-level collision.
    *
    * `args.skipBranch` skips all of this: the run binds to `startBranch`
    * verbatim via [[reuseCurrentBranch]] instead.
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
      flowName: Option[String],
      emit: OrcaEvent => Unit
  )(using InStage, WorkspaceWrite): FeatureBranch =
    val branch =
      if args.skipBranch.value then
        reuseCurrentBranch(startBranch, protectedBranches)
      else
        val strategy =
          branchNaming.getOrElse(BranchNamingStrategy.shortenPrompt)
        val resolvedName = strategy.resolve(args.userPrompt, agent)
        // Resolved once, shared by both fallback triggers below (a
        // protected-name refusal and a git-level `BranchAlreadyExists`
        // collision use the exact same deterministic name).
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
              // Unreachable: `strategy.resolve` always returns an
              // already-slugged name, so `resolve`'s shape check can never
              // refuse it. Guarded defensively rather than assumed.
              throw new OrcaFlowException(
                s"internal error: strategy-resolved branch name '$name' is " +
                  "not a safe ref"
              )
        createFreshBranch(git, protectionChecked, fallback, emit)
    // A just-discovered settings file gets its own commit here — after the
    // branch exists, before the header commit below — so the header commit
    // carries only the progress log its message names (ADR 0019).
    if discovered then commitDiscoveredSettings(git, workDir)
    store.writeHeader(
      ProgressHeader(
        startingBranch = startBranch,
        branch = branch.value,
        promptHash = ProgressStore.hashPrompt(args.userPrompt),
        branchMode =
          if args.skipBranch.value then BranchMode.Reused
          else BranchMode.Created,
        userPrompt = Some(args.userPrompt),
        flowName = flowName
      )
    )
    git.forceCommitOnly(store.path, "orca: progress log")
    branch

  /** Give the just-discovered settings file its own commit (ADR 0019), so the
    * commit that follows carries only what its message names. Called on both
    * lifecycle arms whenever discovery actually wrote the file.
    *
    * [[GitTool.commitOnly]]'s pathspec guarantees the commit carries exactly
    * this one path — anything else dirty or untracked stays out. Not
    * `forceCommitOnly`: a repo that still ignores `.orca/` must keep the file
    * ignored (the migration warning already covers it), so the commit is
    * skipped when [[GitTool.isIgnored]] reports the path excluded. Only the
    * progress log punches through the ignore, for resume correctness.
    */
  private def commitDiscoveredSettings(git: GitTool, workDir: os.Path)(using
      WorkspaceWrite
  ): Unit =
    if !git.isIgnored(OrcaDir.settingsSubPath) then
      git.commitOnly(
        OrcaDir.settingsPath(workDir),
        "orca: stack settings (discovered)"
      )

  /** Skip-branch mode's binding (ADR 0018 amendment): mint `startBranch` — the
    * user's current branch — as the `FeatureBranch` itself, instead of creating
    * a new one. Refuses outright (no fallback rename, unlike the normal
    * minted-name path) when `startBranch` is protected: the user must check out
    * a feature branch themselves before asking to skip creating one.
    *
    * `startBranch` is checked for detached HEAD explicitly, ahead of the
    * generic unsafe-ref case: `git.currentBranch()` reads back the literal
    * string `"HEAD"` when detached, and binding a run to that would commit into
    * an unnamed, GC-eligible state, plus make the resume-side R30 cross-check
    * vacuous (every detached state reads as `"HEAD"`). The generic unsafe-ref
    * case beyond that is a defensive guard, not expected in practice —
    * `startBranch` otherwise comes from `git.currentBranch()`, not untrusted
    * input.
    */
  private def reuseCurrentBranch(
      startBranch: String,
      protectedBranches: Set[String]
  ): FeatureBranch =
    if startBranch == "HEAD" then
      throw new OrcaFlowException(
        "cannot skip branch creation: in detached HEAD — check out a " +
          "branch first, or drop --skip-branch"
      )
    else
      FeatureBranch.resolveReused(startBranch, protectedBranches) match
        case Right(featureBranch) => featureBranch
        case Left(ProtectedBranchRefused(name)) =>
          throw new OrcaFlowException(
            s"cannot skip branch creation: '$name' is a protected branch — " +
              "check out a feature branch first"
          )
        case Left(UnsafeBranchRefRefused(name)) =>
          throw new OrcaFlowException(
            s"current branch '$name' is not a safe git ref — refusing to " +
              "bind the run to it"
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
    * final commit so a merged branch is clean, push that commit when the branch
    * was already published with the log on it, then hand off to
    * [[finishBranch]] for where HEAD lands.
    *
    * Errors during log removal, the cleanup commit, the push, or the branch
    * handoff are cosmetic on an already-successful run — every leg runs through
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
      // Gated on the remote still carrying the log, not on the commit leg
      // succeeding: a resumed teardown hits NothingToCommit yet may still owe
      // the remote a push. The gate is also what keeps this from publishing
      // anything the run didn't — a reused branch that had an upstream before
      // the run only has the log upstream if this run pushed it there.
      bestEffort("push progress-log removal"):
        if git.upstreamHas(setup.store.path) then git.push().orThrow
    finally
      bestEffort("branch handoff"):
        finishBranch(git, setup, returnToStartBranch)

  /** Where HEAD ends up after a successful run. A throwaway feature branch
    * (only orca bookkeeping, no user code vs the start branch) is deleted and
    * HEAD returns to the starting branch. Otherwise the feature branch is kept,
    * and `returnToStartBranch` chooses where HEAD lands — stay on the feature
    * branch (the default) or return to the starting branch (PR flows).
    * Best-effort and success-path-only; never deletes start/protected branches.
    *
    * The throwaway-delete is additionally gated on `setup.branchMode`: a branch
    * orca did not create (`Reused`, skip-branch mode) must never be deleted,
    * even if a tampered header's `startingBranch` is crafted to name some
    * existing branch that happens to diff-blank against the feature branch —
    * that `startingBranch` cross-check doesn't otherwise exist (unlike
    * `branch`'s R30 check against the actual current branch). Residual,
    * accepted: with `branchMode = Reused`, `returnToStartBranch` can still
    * `checkout` a tampered `startingBranch` — navigation only, never
    * destructive.
    */
  private def finishBranch(
      git: GitTool,
      setup: FlowSetup,
      returnToStartBranch: Boolean
  )(using WorkspaceWrite): Unit =
    val throwaway =
      setup.branchMode == BranchMode.Created &&
        setup.featureBranch.value != setup.startBranch &&
        !git.branchHasChangesExcludingOrca(
          setup.startBranch,
          setup.featureBranch.value
        )
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
