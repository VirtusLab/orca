package orca.runner

import orca.{
  BranchNamingStrategy,
  FlowContext,
  FlowControl,
  InStage,
  OrcaArgs,
  OrcaDir,
  OrcaFlowException,
  ReportedFailure,
  RuntimeInStage,
  StackSettings,
  WorkspaceWrite
}
import orca.agents.Agent
import orca.events.OrcaEvent
import orca.util.{JsonFile, TextUtil}
import orca.sessions.SessionStore
import orca.gitref.{BranchName, CommitHash, Head}
import orca.progress.{
  BranchMode,
  FeatureBranch,
  FlowSource,
  ProgressHeader,
  ProgressLog,
  ProgressStore,
  ProtectedBranchRefused,
  RecoveryCheck,
  ThrowawayBranch,
  NotASlugRefused
}
import orca.settings.{AgentSettings, SettingsFile, SettingsScope}
import orca.subprocess.TtyProbe
import orca.tools.{RuntimeGit, UntrackedFiles}
import org.slf4j.LoggerFactory
import ox.either.orThrow

import scala.util.control.NonFatal

/** Flow setup/teardown/recovery lifecycle (ADR 0018 §2.4/§2.5). Owns the
  * privileged, outside-any-user-stage git and progress-store mutations that
  * bracket the body.
  */
object FlowLifecycle:

  /** One run's phases, in mandated order: body → disjoint success/failure
    * teardown (ADR 0018 §2.4/§2.5). [[setup]] (branch + log binding) already
    * ran in `runFlow` before the context was built, so its resolved settings
    * arrive here as a constructor input, not a phase.
    *
    * The body runs inside [[surfaced]] and also runs `teardownFailure` on the
    * way out. `teardownSuccess` runs OUTSIDE `surfaced` — it's already
    * best-effort, and wrapping it would turn a cosmetic teardown failure into a
    * reported failure on a successful run. Since the body's catch rethrows,
    * success teardown is unreachable after a body failure — the two teardowns
    * are structurally disjoint.
    */
  private[orca] def run(
      ctx: FlowContext,
      control: FlowControl,
      flowSetup: FlowSetup,
      debug: Boolean
  )(body: (FlowContext, FlowControl) ?=> Unit): Unit =
    val log = LoggerFactory.getLogger("orca.flow")
    // The whole flow body runs as a top-level stage: an otherwise unhandled
    // exception surfaces as a single Error event. `teardownFailure` runs only
    // here in the body phase, so a success-teardown error can never trigger
    // `discardUncommitted` or strand the user on the feature branch.
    try surfaced(ctx.emit, debug)(body(using ctx, control))
    catch
      case f: ReportedFailure =>
        // If the reset itself fails, attach it as suppressed (rather than
        // replacing `f`), and log/print it too.
        try
          teardownFailure(
            ctx.runtimeGit,
            flowSetup.featureBranch,
            flowSetup.startingTree,
            ctx.emit
          )
        catch
          case NonFatal(t) =>
            f.addSuppressed(t)
            log.debug("teardownFailure failed after body failure", t)
            if debug then t.printStackTrace(System.err)
            ctx.emit(
              OrcaEvent.Step(
                "warning: workspace reset failed after the flow failure — " +
                  "the working tree may still contain the failed run's partial edits"
              )
            )
        throw f
    // Read before teardownSuccess deletes the log.
    val published = PublishedState.from(control.progressStore.loadDetailed())
    teardownSuccess(ctx.runtimeGit, flowSetup, published, ctx.emit)

  /** Runs one lifecycle phase: a failure is reported to `emit` unless already
    * reported, logged (with its stack trace on stderr under `debug`), and
    * rethrown as a [[ReportedFailure]].
    */
  private[orca] def surfaced[T](emit: OrcaEvent => Unit, debug: Boolean)(
      op: => T
  ): T =
    try op
    catch
      case NonFatal(e) =>
        val reported = ReportedFailure.reportOnce(e): cause =>
          emit(OrcaEvent.Error(TextUtil.throwableMessage(cause)))
        LoggerFactory.getLogger("orca.flow").debug("flow aborted", reported)
        if debug then reported.printStackTrace(System.err)
        throw reported

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
      sessionStore: SessionStore,
      featureBranch: FeatureBranch,
      startingHead: Head,
      stackSettings: StackSettings,
      branchMode: BranchMode,
      startingTree: StartingTree,
      startingCommit: Option[CommitHash],
      /** The orca worktree the run happened in, when it happened in one —
        * `--worktree`, or a resume the shell relaunched into one without the
        * flag. The closing summary names it.
        */
      worktree: Option[os.Path]
  )

  /** The branch half of [[FlowSetup]], resolved by whichever of
    * [[SetupSession.bindBranch]]'s arms runs (corrupt log, absent log, or
    * resumed) before the stack-settings half is folded in.
    *
    * `startingCommit` comes from the same arm: a fresh run records the commit
    * it bound at, a resumed one reads back what its header recorded, so both
    * name the state the RUN started from rather than this attempt's.
    */
  private[orca] case class BranchBinding(
      featureBranch: FeatureBranch,
      startingHead: Head,
      branchMode: BranchMode,
      startingCommit: Option[CommitHash]
  )

  /** Bind the run to a branch + progress log before the body runs (ADR 0018
    * §2.4/§2.5), in three phases:
    *   - [[SetupPreflight.run]] — reads only, so every refusal leaves the tree
    *     and branch as the user left them.
    *   - [[SetupSession.settle]] — the cleanliness verdict carried out: stash
    *     or keep, then the peeked log put back if the stash removed it.
    *   - stack discovery, then [[SetupSession.bindBranch]] — the authoritative
    *     read of the log decides fresh vs resume.
    *
    * The tokens are minted between the first two phases: setup is privileged,
    * predating any user stage.
    *
    * On resume `startingHead` is the header's recorded one (where the first
    * attempt started), so a return-to-start goes there, not to the re-run's
    * current feature branch.
    */
  private[orca] def setup(
      args: OrcaArgs,
      // The resolved coding-role agent (ADR 0020): branch-name resolution and
      // stack discovery — the run's privileged pre-body model calls — run here.
      agent: Agent[?],
      git: RuntimeGit,
      workDir: os.Path,
      branchNaming: Option[BranchNamingStrategy],
      // The stack resolution, parsed by `runFlow` upstream (ADR 0020 §6); a
      // malformed file has already aborted before this point.
      resolution: SettingsResolution,
      store: ProgressStore,
      sessionStore: SessionStore,
      // Stamped into a freshly-written header (`freshRun`).
      flowSource: Option[FlowSource],
      emit: OrcaEvent => Unit,
      // The dirty-tree prompt's two terminal dependencies, injected so tests
      // (and any headless caller) decide without one. Production probes real
      // stdin and prompts on stderr; `ask` takes the dirty-file count, which
      // is all the menu shows.
      // Both streams of the exchange must be a terminal: the menu is printed
      // on stderr, so `orca ... 2> run.log` from an interactive shell has a tty
      // stdin but nowhere visible to ask.
      tty: () => Boolean = () => TtyProbe.stdin() && TtyProbe.stderr(),
      ask: Int => DirtyTreeChoice = DirtyTreePolicy.promptOnStderr
  ): FlowSetup =
    val preflight = SetupPreflight.run(
      args,
      git,
      workDir,
      resolution,
      store,
      emit,
      tty,
      ask
    )
    given InStage = RuntimeInStage.token()
    given WorkspaceWrite = RuntimeInStage.workspaceToken()
    val session =
      SetupSession(
        args,
        agent,
        git,
        branchNaming,
        store,
        flowSource,
        emit
      )
    val untracked = session.settle(preflight)
    // Discovery (ADR 0019) is sequenced after the stash, which would sweep a
    // just-written untracked file straight back out of the tree, and before
    // binding, so a failed discovery leaves no branch or header behind.
    val stack = resolveStackSettings(agent, workDir, resolution, emit)
    val binding =
      session.bindBranch(preflight.startingHead, preflight.protectedBranches)
    emit(OrcaEvent.BranchBound(binding.featureBranch.value))
    // Its own commit, on the bound branch, so no later `add -A` sweep carries
    // it under an unrelated message.
    stack match
      case StackOutcome.Discovered(_) => commitDiscoveredSettings(git, workDir)
      case StackOutcome.Configured(_) => ()
    val startingTree = StartingTree.capture(untracked, git, emit)
    FlowSetup(
      store,
      sessionStore,
      binding.featureBranch,
      binding.startingHead,
      stack.settings,
      binding.branchMode,
      startingTree,
      binding.startingCommit,
      // From where the run IS, not from the flag: the shell relaunches a resume
      // inside the worktree its log was found in WITHOUT `--worktree` (the flag
      // would re-derive a path), and that run has to name the tree too.
      Option.when(WorktreeRun.isWorktreeRun(workDir))(workDir)
    )

  /** [[setup]]'s fixed inputs — the coding-role agent, git, the progress store,
    * the emit sink — shared by its mutating phases, so each reads as
    * `session.xxx(...)` rather than repeating the same handful of parameters at
    * every call site.
    */
  private final class SetupSession(
      args: OrcaArgs,
      agent: Agent[?],
      git: RuntimeGit,
      branchNaming: Option[BranchNamingStrategy],
      store: ProgressStore,
      flowSource: Option[FlowSource],
      emit: OrcaEvent => Unit
  ):

    /** Carry out [[SetupPreflight.run]]'s cleanliness verdict, then put back
      * the peeked log if the stash removed it, so the authoritative read in
      * [[bindBranch]] finds it. Returns what failure teardown may then do with
      * untracked files (see [[StartingTree.untracked]]).
      */
    def settle(preflight: Preflight)(using WorkspaceWrite): UntrackedFiles =
      val untracked = preflight.tree match
        case TreePlan.Stash => stashDirtyTree(preflight.dirtyCount)
        case TreePlan.Keep  => keepDirtyTree(preflight.dirtyCount)
      store.restoreIfRemoved(preflight.peeked)
      untracked

    /** Stash whatever is dirty, so the run starts from committed content. Says
      * once that `--keep-changes` lost, and only when it did: the flag holds
      * for a fresh run alone, and there is nothing to ignore on a clean tree. A
      * clean tree also skips `ensureClean` — it would only repeat the caller's
      * `git status` to find nothing to stash.
      */
    private def stashDirtyTree(dirtyCount: Int)(using
        WorkspaceWrite
    ): UntrackedFiles =
      if dirtyCount > 0 then
        if args.target.keepChanges then
          emit(
            OrcaEvent.Step(
              "ignoring --keep-changes: this run already has a progress " +
                "log, so the tree is stashed clean — an interrupted stage's " +
                "partial work must not leak into the stages that re-run"
            )
          )
        git.ensureClean("orca: starting flow")
      UntrackedFiles.Remove

    /** Leave a dirty tree in place, naming the file count once. `Keep` then
      * holds failure teardown back from deleting untracked files it cannot tell
      * apart from the run's own (see [[StartingTree.untracked]]).
      */
    private def keepDirtyTree(dirtyCount: Int): UntrackedFiles =
      if dirtyCount == 0 then UntrackedFiles.Remove
      else
        emit(
          OrcaEvent.Step(
            s"leaving $dirtyCount uncommitted/untracked file(s) in place for the flow"
          )
        )
        UntrackedFiles.Keep

    /** Bind the run to a branch + progress log — resume onto the header's
      * branch for a valid log, warn and start fresh from a corrupt one, start
      * fresh when none exists, and abort on one that cannot be read, since the
      * fresh start would replace a file that may still hold a resumable run.
      * This is the AUTHORITATIVE read of the log: it runs after [[settle]], so
      * a tracked-but-dirty log is classified from its last committed content,
      * never an in-progress edit.
      */
    def bindBranch(
        startingHead: Head,
        protectedBranches: Set[String]
    )(using InStage, WorkspaceWrite): BranchBinding =
      store.loadDetailed() match
        case JsonFile.Read.Corrupt(reason) =>
          warnCorruptLog(reason)
          freshBinding(startingHead, protectedBranches)
        // Readable at the peek; the file changed since.
        case JsonFile.Read.Unreadable(reason) =>
          throw SetupPreflight.unreadableLog(store.path, reason)
        case JsonFile.Read.Absent =>
          freshBinding(startingHead, protectedBranches)
        case JsonFile.Read.Loaded(progressLog) =>
          resumeBinding(progressLog, protectedBranches)

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
            "starting fresh — the stages it recorded will re-run"
        )
      )

    /** Shared by [[bindBranch]]'s corrupt-log and absent-log arms: resolve +
      * create a fresh branch via [[freshRun]], then mint [[BranchMode]] from
      * `args.target` (skip-branch mode never creates a branch, so it reads as
      * `Reused`).
      */
    private def freshBinding(
        startingHead: Head,
        protectedBranches: Set[String]
    )(using InStage, WorkspaceWrite): BranchBinding =
      // Read before the header and settings commits of this setup, so the
      // whole-run review's diff base sits behind everything this run commits.
      // `abortIfNoCommits` has already established a HEAD, and git's own
      // output is a hash, so a miss here is a git failure, not a state.
      val headAtBinding = git
        .headCommit()
        .getOrElse(
          throw new OrcaFlowException("could not resolve HEAD to a commit")
        )
      val branch = freshRun(startingHead, protectedBranches, headAtBinding)
      BranchBinding(
        branch,
        startingHead,
        if args.target.skipBranch then BranchMode.Reused
        else BranchMode.Created,
        Some(headAtBinding)
      )

    /** Fresh run: resolve + create the branch, then commit the header as the
      * branch's first commit. The commit is pathspec-scoped to just the
      * progress-log file (never `add -A`), so a dirty tree the cleanliness
      * policy left in place (`--skip-branch` or `--keep-changes`) reaches the
      * branch only via the first stage's own commit. Shared by the absent-log
      * and corrupt-log arms of [[bindBranch]]. Needs `InStage` (branch-name
      * resolution may call the cheap model) and `WorkspaceWrite` (the git
      * writes).
      *
      * The resolved name is minted into a [[FeatureBranch]] before reaching
      * git: a protected-name collision falls back to a deterministic
      * `flowFallbackName` (same prompt → same fallback, so a resumed run still
      * finds the branch) rather than aborting — an unattended run must not flip
      * between success and failure because the cheap model phrased its summary
      * as "main" this time. [[createFreshBranch]] applies the same policy to a
      * git-level collision.
      *
      * A `--branch` name wins over `branchNaming` and the default strategy and
      * never falls back: [[createRequestedBranch]] refuses it instead of
      * renaming.
      *
      * A `CurrentBranch` target skips all of this: the run binds to the branch
      * `startingHead` is on via [[reuseCurrentBranch]] instead.
      */
    private def freshRun(
        startingHead: Head,
        protectedBranches: Set[String],
        headAtBinding: CommitHash
    )(using InStage, WorkspaceWrite): FeatureBranch =
      val branch =
        if args.target.skipBranch then
          reuseCurrentBranch(startingHead, protectedBranches)
        else
          args.branch match
            case Some(name) =>
              createRequestedBranch(git, name, protectedBranches)
            case None =>
              createNamedByStrategy(
                args.userPrompt,
                agent,
                git,
                branchNaming,
                protectedBranches,
                emit
              )
      store.writeHeader(
        ProgressHeader(
          startingBranch = startingHead.branch,
          branch = branch,
          branchMode =
            if args.target.skipBranch then BranchMode.Reused
            else BranchMode.Created,
          userPrompt = args.userPrompt,
          flow = flowSource,
          startingCommit = headAtBinding
        )
      )
      git.forceCommitOnly(store.path, "orca: progress log")
      branch

    /** Resume onto the header's existing branch. Validates the untrusted header
      * against the protected set before any destructive action — invalid is a
      * hard abort, not a silent fresh start — and checks the current branch
      * matches the one the header names (R30): a log surfaced on the wrong
      * branch (e.g. carried by a merge) aborts rather than resuming there.
      * Returns the header's recorded starting head (where the FIRST attempt
      * started), not this attempt's, so return-to-start lands correctly.
      */
    private def resumeBinding(
        log: ProgressLog,
        protectedBranches: Set[String]
    ): BranchBinding =
      val header = log.header
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
      val recorded = header.branch.value
      args.branch
        .filter(_ != header.branch)
        .foreach: requested =>
          throw new OrcaFlowException(
            s"refusing to resume: this run is already bound to branch " +
              s"'$recorded' — omit --branch or pass " +
              s"--branch $recorded (got '${requested.value}')"
          )
      val current = git.head()
      if current != Head.OnBranch(header.branch) then
        throw new OrcaFlowException(
          s"progress log for branch '$recorded' found while on " +
            s"${current.describe} — was it merged? aborting rather than " +
            "resuming against the wrong branch"
        )
      // The FIRST attempt's commit, read back from the header: this attempt's
      // HEAD has moved on by every stage the interrupted run committed, and a
      // whole-run review must still see those. Dropped unless git can still
      // diff against it — a rebase or a fresh clone leaves a hash that would
      // otherwise widen the review to unrelated history.
      val startingCommit =
        Some(header.startingCommit).filter(git.isAncestorOfHead)
      // Ahead of setup's settings commit, so the reported HEAD is the tree the
      // recorded stages left behind rather than orca's own bookkeeping.
      announceResume(featureBranch, startingCommit, log.entries.size)
      BranchBinding(
        featureBranch,
        header.startingHead,
        header.branchMode,
        startingCommit
      )

    /** What a resumed run says about itself, once. `createBranch` and
      * `checkout` announce the branch on every other path; this arm binds an
      * existing branch and takes neither, so nothing else would name it.
      *
      * `startingCommit` is the post-filter value, so the hash shown is always
      * one git can still resolve. The second line is the run's whole account of
      * the replay — each replayed stage prints only its own marker — so it
      * carries how much is skipped and what state the tree is in.
      *
      * "not carried over" rather than "discarded": a run resumed after a clean
      * failure had that work reset away, but one resumed after a kill has it
      * auto-stashed instead (ADR 0018 R4), and the cleanliness Step says so.
      */
    private def announceResume(
        branch: FeatureBranch,
        startingCommit: Option[CommitHash],
        recordedStages: Int
    ): Unit =
      val from =
        startingCommit.fold("")(c => s" — this run started from ${c.short}")
      emit(OrcaEvent.Step(s"on branch '${branch.value}'$from"))
      val at = git
        .headCommit()
        .fold("")(c => s", tree at ${c.short}")
      emit(
        OrcaEvent.Step(
          s"resuming: $recordedStages stage(s) already recorded$at; the " +
            "interrupted stage's uncommitted work was not carried over"
        )
      )

  /** Resolve the stack settings for the run (ADR 0019 §7): pass through
    * `resolution`'s already-resolved settings, or run [[StackDiscovery]] and
    * write the settings file when discovery is needed — the whole rendered file
    * for an absent one, or the discovered entries appended below an agents-only
    * hand-written file's untouched agent lines (ADR 0020 §7). A replace, not a
    * create, even for an absent file: a stash upstream may already have swept
    * an untracked file out of the tree. A [[StackOutcome.Discovered]] result
    * means this call wrote the file, which [[setup]] then commits. A discovery
    * failure aborts as a surfaced failure — no degrade-to-empty-file (see
    * [[StackDiscovery]]).
    */
  private def resolveStackSettings(
      agent: Agent[?],
      workDir: os.Path,
      resolution: SettingsResolution,
      emit: OrcaEvent => Unit
  )(using InStage): StackOutcome =
    resolution match
      case SettingsResolution.Resolved(settings) =>
        StackOutcome.Configured(settings)
      case SettingsResolution.Overridden(settings) =>
        StackOutcome.Configured(settings)
      case SettingsResolution.NeedsDiscovery(existingContent) =>
        val (settings, entries) =
          StackDiscovery.discover(agent, workDir, emit, existingContent)
        val fileText = existingContent match
          case None => SettingsFile.render(entries)
          case Some(content) =>
            content + "\n" + SettingsFile.renderAppend(entries)
        OrcaDir.settingsFile(workDir).replace(fileText)
        emit(
          OrcaEvent.Step(
            "written to .orca/settings.properties — review and edit as needed."
          )
        )
        StackOutcome.Discovered(settings)

  /** The run's stack settings, and whether [[resolveStackSettings]] just wrote
    * them to the settings file, which then still needs its own commit.
    */
  private enum StackOutcome(val settings: StackSettings):
    case Configured(value: StackSettings) extends StackOutcome(value)
    case Discovered(value: StackSettings) extends StackOutcome(value)

  /** Outcome of the pre-`ensureClean` stack read: the values the flow passed
    * (`flow(stackSettings = Some(...))`), the values the project file
    * configures, or the marker that auto-discovery must run. `NeedsDiscovery`
    * carries the existing file content (ADR 0020 §7): `None` when the file is
    * absent or blank (write the whole file), `Some(content)` for an agents-only
    * hand-written file (append the stack entries, leaving agent lines
    * untouched). Content is captured pre-stash so a hand-written file the stash
    * sweeps out of a dirty tree is not lost.
    */
  private[runner] enum SettingsResolution:
    case Overridden(settings: StackSettings)
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
    * discovery trigger (ADR 0019 amendment 2026-09-23): a present file
    * configuring a stack key ([[orca.settings.ParsedSettings]]`.stack`)
    * resolves; an absent, blank, or stack-silent file needs discovery.
    */
  private[orca] def readSettings(
      workDir: os.Path,
      globalSettingsPath: os.Path,
      stackOverride: Option[StackSettings]
  ): SettingsRead =
    val projectPath = OrcaDir.settingsPath(workDir)
    // A repo can commit `.orca/settings.properties` as a symlink pointing
    // outside the tree, which `os.read` follows. Refused here, before any tree
    // mutation, so the abort leaves the current branch untouched.
    OrcaDir.assertNoOrcaSymlinks(workDir, projectPath)
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
        case Some(settings) => SettingsResolution.Overridden(settings)
        case None =>
          projectParsed.flatMap(_.stack) match
            case Some(settings) => SettingsResolution.Resolved(settings)
            case None =>
              SettingsResolution.NeedsDiscovery(
                projectContent.filterNot(_.isBlank)
              )
    SettingsRead(stack, projectAgents, globalAgents)

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

  /** Create the branch `branchNaming` (or the default strategy) derives from
    * `userPrompt`, falling back to `flow-<hash>` on a protected or existing
    * name.
    */
  private def createNamedByStrategy(
      userPrompt: String,
      agent: Agent[?],
      git: RuntimeGit,
      branchNaming: Option[BranchNamingStrategy],
      protectedBranches: Set[String],
      emit: OrcaEvent => Unit
  )(using InStage, WorkspaceWrite): FeatureBranch =
    val strategy = branchNaming.getOrElse(BranchNamingStrategy.shortenPrompt)
    val resolvedName = strategy.resolve(userPrompt, agent)
    // Resolved once, shared by both fallback triggers below (a
    // protected-name refusal and a git-level `BranchAlreadyExists`
    // collision use the exact same deterministic name).
    val fallback = resolveFallback(userPrompt, protectedBranches)
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
        case Left(NotASlugRefused(name)) =>
          // Unreachable: `strategy.resolve` always returns an
          // already-slugged name, so `resolve`'s shape check can never
          // refuse it. Guarded defensively rather than assumed.
          throw new OrcaFlowException(
            s"internal error: strategy-resolved branch name '$name' is " +
              "not a slug"
          )
    createFreshBranch(git, protectionChecked, fallback, emit)

  /** Create the `--branch` name as given. Refuses (no fallback rename) when it
    * is one of this repo's protected branches — the detected default included,
    * which parse time cannot know — or already exists.
    */
  private def createRequestedBranch(
      git: RuntimeGit,
      name: BranchName,
      protectedBranches: Set[String]
  )(using WorkspaceWrite): FeatureBranch =
    val featureBranch = SetupPreflight.requestedBranch(name, protectedBranches)
    git.createBranch(featureBranch) match
      case Right(()) => featureBranch
      case Left(_)   => throw SetupPreflight.requestedBranchExists(name)

  /** Give the just-discovered settings file its own commit (ADR 0019), so no
    * later commit carries it under an unrelated message.
    *
    * [[RuntimeGit.commitOnly]]'s pathspec guarantees the commit carries exactly
    * this one path — anything else dirty or untracked stays out. Not
    * `forceCommitOnly`: a repo that still ignores `.orca/` must keep the file
    * ignored (the migration warning already covers it), so the commit is
    * skipped when [[RuntimeGit.isIgnored]] reports the path excluded. Only the
    * progress log punches through the ignore, for resume correctness.
    */
  private def commitDiscoveredSettings(git: RuntimeGit, workDir: os.Path)(using
      WorkspaceWrite
  ): Unit =
    if !git.isIgnored(OrcaDir.settingsSubPath) then
      git.commitOnly(
        OrcaDir.settingsPath(workDir),
        "orca: stack settings (discovered)"
      )

  /** Skip-branch mode's binding (ADR 0018 amendment): mint the user's current
    * branch as the `FeatureBranch` itself, instead of creating a new one.
    * Refuses outright (no fallback rename, unlike the normal minted-name path)
    * when that branch is protected: the user must check out a feature branch
    * themselves before asking to skip creating one. Refuses a detached HEAD
    * too: binding a run there would commit into an unnamed, GC-eligible state.
    */
  private def reuseCurrentBranch(
      startingHead: Head,
      protectedBranches: Set[String]
  ): FeatureBranch =
    startingHead match
      case Head.Detached(_) =>
        throw new OrcaFlowException(
          "cannot skip branch creation: in detached HEAD — check out a " +
            "branch first, or drop --skip-branch"
        )
      case Head.OnBranch(current) =>
        FeatureBranch.resolveReused(current, protectedBranches) match
          case Right(featureBranch) => featureBranch
          case Left(ProtectedBranchRefused(name)) =>
            throw new OrcaFlowException(
              s"cannot skip branch creation: '$name' is a protected branch — " +
                "check out a feature branch first"
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
      git: RuntimeGit,
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
    git.createBranch(candidate) match
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
          git.createBranch(fallback) match
            case Right(()) => fallback
            case Left(_)   => doubleCollisionAbort(fallback.value)

  /** Run a teardownSuccess leg best-effort: any `NonFatal` failure is caught
    * and debug-logged (never printed, never surfaced) so it cannot escape
    * teardown, trigger the failure path, or strand the user. `what` names the
    * leg for the log line.
    */
  private def bestEffort(what: String)(op: => Unit): Unit =
    val _ = bestEffortRead(what)(Some(op))

  /** [[bestEffort]] for a leg the closing summary reads a value from: a failure
    * yields `None`, so a broken git read costs the summary a line rather than
    * escaping teardown.
    */
  private def bestEffortRead[T](what: String)(op: => Option[T]): Option[T] =
    val log = LoggerFactory.getLogger("orca.flow")
    try op
    catch
      case NonFatal(e) =>
        log.debug(s"teardownSuccess: $what failed (cosmetic, swallowed)", e)
        None

  /** Successful teardown (ADR 0018 §2.5): remove the progress-log file in a
    * final commit so a merged branch is clean, drop the run's cached session
    * records with it, push that commit when the branch was already published
    * with the log on it, then hand off to [[finishBranch]] for where HEAD
    * lands.
    *
    * Errors during log removal, the cleanup commit, the push, or the branch
    * handoff are cosmetic on an already-successful run — every leg runs through
    * [[bestEffort]]. A missing progress-log file (the ordinary "already
    * removed" case) stays fully silent; every other failure is debug-logged.
    *
    * The closing summary ([[ClosingSummary]]) straddles the whole sequence: its
    * change set is counted first, while the feature branch is still checked
    * out, and it is emitted last, once [[finishBranch]] has settled which
    * branch the user is left on — which is why [[RunChanges]] carries the
    * branch it was counted on.
    */
  private[runner] def teardownSuccess(
      git: RuntimeGit,
      setup: FlowSetup,
      published: PublishedState,
      emit: OrcaEvent => Unit
  ): Unit =
    // Teardown runs outside any user stage, so it mints its own
    // `WorkspaceWrite`. No LLM call happens here, so `InStage` isn't needed.
    given WorkspaceWrite = RuntimeInStage.workspaceToken()
    val changes =
      try
        // First, while the feature branch is still checked out: the count is
        // what `git diff` against the base shows there.
        val counted = bestEffortRead("count changed files"):
          setup.startingCommit.map: base =>
            RunChanges(
              base,
              git.trackedChangedFiles(base).size,
              setup.featureBranch
            )
        bestEffort("remove progress log")(setup.store.remove())
        // Dropped with the log, and for the same reason: a later run of this
        // prompt is a new run, not a resume, so it must open fresh backend
        // conversations rather than continue this one's.
        bestEffort("remove session records")(setup.sessionStore.discard())
        // Pathspec-scoped to the log file, so uncommitted files the cleanliness
        // policy left in the tree stay out of this bookkeeping commit;
        // force-staged, because `.orca/` may be gitignored. A log never
        // committed, or already removed by an earlier teardown, fails the stage
        // or the commit — cosmetic, swallowed by bestEffort.
        bestEffort("commit progress-log removal"):
          git.forceCommitOnly(setup.store.path, "orca: remove progress log")
        // Gated on the remote still carrying the log, not on the commit leg
        // succeeding: a resumed teardown's commit finds nothing to commit yet
        // may still owe the remote a push. The gate is also what keeps this
        // from publishing anything the run didn't — a reused branch that had an
        // upstream before the run only has the log upstream if this run pushed
        // it there.
        bestEffort("push progress-log removal"):
          if git.upstreamHas(setup.store.path) then git.push().orThrow
        counted
      finally
        bestEffort("branch handoff"):
          finishBranch(git, setup, published)
    bestEffort("closing summary"):
      ClosingSummary
        .lines(git.head(), changes, setup.worktree, published.work)
        .foreach(line => emit(OrcaEvent.Step(line)))

  /** Where HEAD ends up after a successful run. A throwaway feature branch
    * ([[ThrowawayBranch]]) is deleted and HEAD returns to where the run
    * started. Otherwise the feature branch is kept, and [[BranchHandoff]]
    * chooses where HEAD lands. Best-effort and success-path-only; never deletes
    * start/protected branches.
    *
    * The delete runs only on a [[PublishedState.NotPublished]] log — a branch
    * whose log records published work, or whose log could not be read at all,
    * is kept however empty it looks against the start branch: what was
    * published points at what was pushed, and the user needs the branch to
    * answer it.
    */
  private def finishBranch(
      git: RuntimeGit,
      setup: FlowSetup,
      published: PublishedState
  )(using WorkspaceWrite): Unit =
    // No starting commit (a resume whose recorded one is gone) means nothing
    // to measure against, so the branch is kept.
    val throwaway =
      published.allowsBranchDelete && setup.startingCommit.exists: start =>
        ThrowawayBranch.isThrowaway(
          git,
          setup.branchMode,
          startingCommit = start,
          featureBranch = setup.featureBranch
        )
    if throwaway then
      returnToStart(git, setup.startingHead)
      git.deleteBranch(setup.featureBranch)
    else
      BranchHandoff.of(setup.branchMode, setup.worktree, published) match
        case BranchHandoff.ReturnToStart =>
          returnToStart(git, setup.startingHead)
        case BranchHandoff.StayPut => ()

  /** Put HEAD back where the run started: its start branch, or the detached
    * start commit.
    */
  private def returnToStart(git: RuntimeGit, startingHead: Head)(using
      WorkspaceWrite
  ): Unit =
    startingHead match
      // The branch existed when this run began; if it's gone mid-run that's
      // genuinely exceptional, not a case to paper over by creating it anew.
      case Head.OnBranch(name) => git.checkout(name).orThrow
      case Head.Detached(at)   => git.checkoutDetached(at)

  /** Failure teardown (ADR 0018 §2.5): discard the failed stage's uncommitted
    * partial edits with `git reset --hard` (which restores the last committed
    * log) plus, when `startingTree` allows it, the files the stage newly
    * created, staying on the feature branch so the next attempt resumes in
    * place. Kept tracked changes that no commit has carried yet are put back.
    *
    * Touches nothing when HEAD is off `featureBranch`: the body moved it, so
    * the edits there are not known to be only the failed stage's.
    */
  private[orca] def teardownFailure(
      git: RuntimeGit,
      featureBranch: FeatureBranch,
      startingTree: StartingTree,
      emit: OrcaEvent => Unit
  ): Unit =
    given WorkspaceWrite = RuntimeInStage.workspaceToken()
    git.head() match
      case Head.OnBranch(branch) if branch == featureBranch =>
        // Names WHY the reset is about to discard changes — the reset's own
        // "Discarded uncommitted changes" Step alone would read as
        // unexplained data loss.
        val discarding = startingTree.untracked match
          case UntrackedFiles.Remove =>
            "uncommitted changes and the files the failed stage created"
          case UntrackedFiles.Keep =>
            "uncommitted edits to tracked files (new files stay)"
        emit(
          OrcaEvent.Step(
            s"recovering from the failure — discarding $discarding; re-run " +
              "the same command (the same flow with the same prompt) to " +
              "resume from the last completed stage"
          )
        )
        git.discardUncommitted(startingTree.untracked)
        startingTree.restore(git, emit)
      case elsewhere =>
        emit(
          OrcaEvent.Step(
            s"warning: the flow failed on ${elsewhere.describe}, not on its " +
              s"branch '${featureBranch.value}' — leaving the working tree " +
              "as it is; commit or stash what is there, check out " +
              s"'${featureBranch.value}' and re-run the same command to resume"
          )
        )
