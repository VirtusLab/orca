package orca.runner

import orca.{
  BranchNamingStrategy,
  FlowContext,
  InStage,
  OrcaArgs,
  OrcaFlowException
}
import orca.agents.{BackendTag, Agent, SessionId, WireSessionId}
import orca.progress.{ProgressHeader, ProgressStore, RecoveryCheck}
import orca.tools.GitTool

import scala.util.control.NonFatal

/** Flow setup/teardown/recovery lifecycle (ADR 0018 §2.4/§2.5). Extracted from
  * the `flow` entry point so `flow.scala` holds only the entry point and
  * orchestration: this object owns the privileged, outside-any-user-stage git
  * and progress-store mutations that bracket the body.
  */
object FlowLifecycle:

  /** Replay the persisted resume-wire-id map (ADR 0018 §2.6) into each
    * session's OWN agent's in-memory registry, so a resumed run resumes against
    * the right wire id and the existence probes target the right id. Reads
    * every [[orca.progress.SessionRecord]] that carries a `resumeWireId` and
    * registers it — via [[orca.agents.Agent.registerResumeWireId]] — into the
    * agent [[targetAgent]] resolves for the record's `backend` tag: untagged
    * (older) records go to `lead`, a tag matching one of `ctx`'s per-backend
    * accessors goes there, and a tag matching none of them (an edited log) is
    * skipped rather than guessed.
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
      agent <- targetAgent(ctx, lead, record.backend)
    do register(agent, record.id, wireId)

  /** Untagged records (older logs) go to the lead — the pre-tagging behaviour.
    * A tag that matches no accessor (edited log) is skipped, not guessed.
    * Resolving an accessor may construct that backend's default agent; that is
    * correct — a record for backend X means the body will use X again anyway.
    */
  private def targetAgent(
      ctx: FlowContext,
      lead: Agent[?],
      tag: Option[String]
  ): Option[Agent[?]] =
    tag match
      case None => Some(lead)
      case Some(t) =>
        BackendTag.values
          .find(_.toString == t)
          .map:
            case BackendTag.ClaudeCode => ctx.claude
            case BackendTag.Codex      => ctx.codex
            case BackendTag.Opencode   => ctx.opencode
            case BackendTag.Pi         => ctx.pi
            case BackendTag.Gemini     => ctx.gemini

  private def register[B <: BackendTag](
      agent: Agent[B],
      id: String,
      wire: String
  ): Unit =
    agent.registerResumeWireId(SessionId[B](id), WireSessionId[B](wire))

  /** Outcome of [[setup]]: the resolved progress store, the feature branch the
    * run is bound to, and the starting branch to restore on success.
    */
  private[orca] case class FlowSetup(
      store: ProgressStore,
      featureBranch: String,
      startBranch: String
  )

  /** Bind the run to a branch + progress log before the body runs (ADR 0018
    * §2.4/§2.5). Records the starting branch, snapshots the log file, stashes a
    * dirty tree, then either resumes an existing log or starts fresh (resolve a
    * branch name, create it, write + commit the header). All git/store
    * mutations run with a runtime-minted `InStage` — setup is privileged,
    * predating any user stage.
    *
    * The progress header is **untrusted input** on load (the log is
    * human-visible and pushable), so a resumed run:
    *   - Snapshots the log file BEFORE `ensureClean` and restores it if the
    *     stash removed it, so the header is always readable.
    *   - Validates the header before any destructive action (safe refs,
    *     prompt-hash match, no protected feature branch). A
    *     parseable-but-invalid header is a HARD abort (`OrcaFlowException`),
    *     not a silent fresh start — it signals tampering or a mismatch. (An
    *     *unparseable* log stays `store.load() == None` → fresh run; that path
    *     is separate.)
    *   - Cross-checks that the current branch is the one the header records
    *     (the in-place invariant): a log that surfaced on a branch it does not
    *     name (e.g. its feature branch was merged, carrying the log along)
    *     aborts rather than resuming against the wrong branch.
    *
    * On resume `startBranch` is the header's recorded `startingBranch` (the
    * ORIGINAL branch at first run), so when a run does return to start (a PR
    * flow via `returnToStartBranch`, or a throwaway) it goes to that original
    * branch — exactly like a fresh run — not the re-run's current (feature)
    * branch.
    */
  private[orca] def setup(
      args: OrcaArgs,
      agent: Agent[?],
      git: GitTool,
      branchNaming: Option[BranchNamingStrategy],
      store: ProgressStore
  ): FlowSetup =
    given InStage = InStage.unsafe
    val startBranch = git.currentBranch()
    // Snapshot the log file before the stash, restore it if the stash
    // removed it — so an uncommitted/untracked log is still readable below.
    val snapshot = snapshotLog(store.path)
    val _ = git.ensureClean("orca: starting flow")
    restoreLogIfMissing(store.path, snapshot)
    store.load() match
      case Some(log) =>
        val header = log.header
        // Validate the untrusted header before any destructive action. The
        // protected set is the main/master floor plus the repo's ACTUAL default
        // branch (best-effort), so a tampered header naming e.g. `trunk` as a
        // feature branch is refused too.
        val protectedBranches =
          Set("main", "master") ++ git.defaultBranch().map(_.toLowerCase)
        RecoveryCheck.validateHeader(
          header,
          args.userPrompt,
          protectedBranches
        ) match
          case Left(reason) =>
            throw new OrcaFlowException(
              s"refusing to resume: progress log header failed validation ($reason)"
            )
          case Right(()) => ()
        // Only resume IN PLACE. If the log surfaced on a branch it does not
        // name, it was likely carried here by a merge — abort, don't replay.
        val current = git.currentBranch()
        if current != header.branch then
          throw new OrcaFlowException(
            s"progress log for branch '${header.branch}' found while on " +
              s"'$current' — was it merged? aborting rather than resuming " +
              "against the wrong branch"
          )
        // Resume in place: already on header.branch. The recorded start branch
        // (where a PR flow / throwaway returns to) is the ORIGINAL one, not this
        // feature branch.
        FlowSetup(store, header.branch, header.startingBranch)
      case None =>
        // Fresh run: resolve + create the branch, then commit the header so it
        // is the branch's first commit.
        val strategy =
          branchNaming.getOrElse(BranchNamingStrategy.shortenPrompt)
        val branch = strategy.resolve(args.userPrompt, agent)
        git.checkoutOrCreate(branch)
        store.writeHeader(
          ProgressHeader(
            startingBranch = startBranch,
            branch = branch,
            promptHash = ProgressStore.hashPrompt(args.userPrompt)
          )
        )
        git.forceAdd(store.path)
        val _ = git.commit("orca: progress log")
        FlowSetup(store, branch, startBranch)

  /** Read the bytes of the progress-log file if it exists. Returns `None` when
    * the file is absent — the normal fresh-run case and the case where the log
    * is committed (so the stash can't remove it).
    */
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

  /** Successful teardown (ADR 0018 §2.5): remove the progress-log file in a
    * final commit so a merged branch is clean, then hand off to
    * [[finishBranch]] for where HEAD lands — stay on the feature branch
    * (default), return to the starting branch (`returnToStartBranch`), or
    * delete a throwaway and return to start.
    *
    * Errors during log removal, the cleanup commit, or the branch handoff are
    * cosmetic — swallowed (in a `finally`) so they don't trigger the failure
    * path or strand the user.
    */
  private[orca] def teardownSuccess(
      git: GitTool,
      setup: FlowSetup,
      returnToStartBranch: Boolean
  ): Unit =
    // Teardown is runtime code running outside any user stage, so it mints its
    // own `InStage` — the runtime is the privileged token constructor.
    given InStage = InStage.unsafe
    try
      // Best-effort: a missing file (already gone) or a failing cleanup commit is
      // cosmetic on an already-successful run, so neither must escape teardown.
      try os.remove(setup.store.path)
      catch
        case _: java.nio.file.NoSuchFileException => ()
        // `add -A` in commit picks up the removal; NothingToCommit (a Left) means
        // it was never committed — harmless. A genuine commit failure is swallowed:
        // the run already succeeded and the progress file is gone from the tree.
      try
        val _ = git.commit("orca: remove progress log")
      catch case NonFatal(_) => ()
    finally
      try finishBranch(git, setup, returnToStartBranch)
      catch case NonFatal(_) => ()

  /** Where HEAD ends up after a successful run. A throwaway feature branch
    * (only orca bookkeeping, no user code vs the start branch — diff excluding
    * `.orca/` is empty) is always deleted and HEAD returns to the starting
    * branch: there's nothing to keep. Otherwise the feature branch is kept, and
    * `returnToStartBranch` chooses where HEAD lands — stay on the feature
    * branch (the default, so the user ends on the work) or return to the
    * starting branch (PR flows, done with the branch once the PR is up).
    * Best-effort and success-path-only; never deletes start/protected branches.
    */
  private def finishBranch(
      git: GitTool,
      setup: FlowSetup,
      returnToStartBranch: Boolean
  )(using InStage): Unit =
    val throwaway =
      setup.featureBranch != setup.startBranch &&
        git
          .diffBranchExcludingOrca(setup.startBranch, setup.featureBranch)
          .isBlank
    if throwaway then
      git.checkoutOrCreate(setup.startBranch)
      git.deleteBranch(setup.featureBranch)
    else if returnToStartBranch then git.checkoutOrCreate(setup.startBranch)
    // else: stay on the feature branch (the default).

  /** Failure teardown (ADR 0018 §2.5): discard the failed stage's uncommitted
    * partial edits with `git reset --hard` (which restores the last committed
    * log), and stay on the feature branch so the next run resumes in place.
    */
  private[orca] def teardownFailure(git: GitTool): Unit =
    // Runtime teardown mints its own token, as in `teardownSuccess`.
    given InStage = InStage.unsafe
    git.resetHard()
