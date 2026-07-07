package orca.runner

import orca.{
  BranchNamingStrategy,
  FlowContext,
  FlowControl,
  InStage,
  OrcaArgs,
  OrcaFlowException,
  RuntimeInStage,
  WorkspaceWrite,
  throwableMessage
}
import orca.agents.{BackendTag, Agent, SessionId, WireSessionId}
import orca.events.OrcaEvent
import orca.progress.{
  FeatureBranch,
  ProgressHeader,
  ProgressStore,
  ProtectedBranchRefused,
  RecoveryCheck
}
import orca.tools.GitTool
import org.slf4j.LoggerFactory
import ox.either.orThrow

import scala.util.control.NonFatal

/** Marker that a flow failure has ALREADY been reported to the user's event
  * surface (an `OrcaEvent.Error` was emitted, the stack logged, and — under
  * `--verbose`/debug — printed). Thrown by [[FlowLifecycle]]'s `surfaced`
  * bracket after it reports `cause`, so `flow()` may discard it without
  * re-reporting: the user has already seen the message.
  *
  * The contract is the whole point of the type: ANY other `NonFatal` exception
  * escaping `runFlow` unwrapped means a code path that was NOT bracketed, i.e.
  * an UNSURFACED failure — `flow()` treats that as the backstop and prints it
  * to stderr rather than exiting silently. A `case class` so tests can
  * pattern-match `case SurfacedFlowFailure(cause) => ...` and inspect the
  * original directly.
  */
private[orca] final case class SurfacedFlowFailure(cause: Throwable)
    extends RuntimeException(cause)

/** Flow setup/teardown/recovery lifecycle (ADR 0018 §2.4/§2.5). Extracted from
  * the `flow` entry point so `flow.scala` holds only the entry point and
  * orchestration: this object owns the privileged, outside-any-user-stage git
  * and progress-store mutations that bracket the body.
  */
object FlowLifecycle:

  /** The complete phase protocol for one run, in its mandated order: setup
    * (branch + log binding) → session rehydration → body → disjoint
    * success/failure teardown. Extracted here so the ordering invariants that
    * used to live as comments in the runner's entry point have one executable
    * owner (ADR 0018 §2.4/§2.5). The context must be fully constructed (setup
    * resolves the leading agent for branch naming and reads `ctx.git`).
    *
    * Failure path: three phases — setup, rehydration, and the body — run inside
    * the `surfaced` bracket, which reports the error (unless the exception
    * already reported itself), logs, and rethrows a [[SurfacedFlowFailure]] so
    * `flow()` never exits without an explanation. The body phase additionally
    * runs `teardownFailure` on the way out.
    *
    * Success path runs `teardownSuccess` OUTSIDE `surfaced`, deliberately: it
    * is already internally best-effort (every leg is wrapped in `bestEffort`,
    * so nothing `NonFatal` can escape it), and wrapping a phase that can't fail
    * with a bracket meant for reporting failures would be directionally wrong —
    * it would convert a future non-`bestEffort` leg into a *reported, failed*
    * successful run instead of the silent cosmetic failure `teardownSuccess`'s
    * own contract promises. So the phase protocol is three bracketed phases
    * plus one best-effort phase, not four bracketed ones.
    *
    * The failure and success teardowns are structurally disjoint — the body
    * catch rethrows, so success teardown is unreachable on a body failure.
    */
  private[orca] def run[B <: BackendTag](
      args: OrcaArgs,
      ctx: DefaultFlowContext[B],
      branchNaming: Option[BranchNamingStrategy],
      returnToStartBranch: Boolean,
      debug: Boolean
  )(body: FlowControl ?=> Unit): Unit =
    val log = LoggerFactory.getLogger("orca.flow")
    // Report/log/wrap bracket applied to every phase that CAN fail — setup's
    // resume refusals, rehydration, and the body — so none of them can reach
    // `flow()` unreported and exit 1 in silence. Phase-agnostic by design: it
    // reports once (reusing the context's reported-set so it never
    // double-prints a failure a nested stage already surfaced), logs, prints
    // the stack under `--verbose`/debug, then throws `SurfacedFlowFailure`.
    // It carries NO teardown side effect — `teardownFailure` (git reset) is
    // the body phase's job alone (below), never setup's. Success teardown is
    // NOT wrapped here — see its own scaladoc for why.
    def surfaced[T](op: => T): T =
      try op
      catch
        case NonFatal(e) =>
          ctx.reportOnce(e)(ctx.emit(OrcaEvent.Error(throwableMessage(e))))
          log.debug("flow aborted", e)
          if debug then e.printStackTrace(System.err)
          throw SurfacedFlowFailure(e)
    val flowSetup =
      surfaced(
        setup(
          args,
          ctx.agent,
          ctx.git,
          branchNaming,
          ctx.progressStore,
          ctx.emit
        )
      )
    surfaced(rehydrateSessions(ctx, ctx.agent, ctx.progressStore))
    // The whole flow body runs as a top-level stage: an otherwise
    // unhandled exception surfaces as a single Error event (the same
    // message a stage failure shows). A nested stage / `fail` marks the
    // throwable reported on the context once it has surfaced it, so
    // `surfaced`'s `reportOnce` above doesn't re-report it. The stack goes to
    // the trace file only (DEBUG, below the console's WARN threshold);
    // `--verbose` also prints it to stderr.
    //
    // Teardown separation: body-failure and body-success teardowns are
    // completely disjoint — structurally, not flag-guarded: the catch below
    // rethrows, so success teardown is unreachable on failure. A
    // success-teardown error (e.g. a cosmetic cleanup-commit failure) must
    // NOT trigger the failure teardown (`resetHard`), and must NOT strand
    // the user on the feature branch. `teardownFailure` runs OUTSIDE
    // `surfaced` (which is side-effect-free) and only here, in the body phase.
    try surfaced(body(using ctx))
    catch
      case f @ SurfacedFlowFailure(e) =>
        // `e` was already reported by `surfaced`. Discard the failed stage's
        // partial edits; if the reset ITSELF fails, attach it as suppressed so
        // it travels with `e` rather than masking the original body failure
        // the user needs to see. `e` was reported (and its stack printed
        // under `--verbose`) BEFORE this reset ran, so the suppressed
        // teardown failure would otherwise never reach the console/trace —
        // log and (under the same `--verbose`/debug flag) print it here. Also
        // emit a user-visible `Step` (in ADDITION to, not instead of, the
        // suppressed exception + debug log): `emit` is total (the dispatcher
        // quarantines a misbehaving listener), so it's safe to call from this
        // catch, and the user needs to know the working tree may still hold
        // the failed run's partial edits.
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
    * `DefaultFlowContext` holds all five per-backend agents as eager
    * constructor vals, so resolving an accessor here just reads the
    * already-constructed agent — touching it is safe even for backends the flow
    * body never otherwise uses.
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
    *
    * `featureBranch` is a [[FeatureBranch]], not a bare `String`: both arms of
    * `setup` only ever construct one via [[FeatureBranch.resolve]] (the fresh
    * arm directly, the resume arm via [[RecoveryCheck.validateHeader]]), so
    * "delete/checkout an unvalidated name" is unrepresentable here — a
    * protected name can never reach this field, not just "doesn't today because
    * nothing upstream produces one." `finishBranch` unwraps `.value` only at
    * the actual `GitTool` call sites.
    */
  private[orca] case class FlowSetup(
      store: ProgressStore,
      featureBranch: FeatureBranch,
      startBranch: String
  )

  /** Bind the run to a branch + progress log before the body runs (ADR 0018
    * §2.4/§2.5). Records the starting branch, snapshots the log file, stashes a
    * dirty tree, then either resumes an existing log or starts fresh (resolve a
    * branch name, create it, write + commit the header). All git/store
    * mutations run with a runtime-minted `WorkspaceWrite`, and branch-name
    * resolution (which may call the cheap model) with a runtime-minted
    * `InStage` — setup is privileged, predating any user stage.
    *
    * The progress header is **untrusted input** on load (the log is
    * human-visible and pushable), so a resumed run:
    *   - Snapshots the log file BEFORE `ensureClean` and restores it if the
    *     stash removed it, so the header is always readable.
    *   - Validates the header before any destructive action (safe refs,
    *     prompt-hash match, no protected feature branch). A
    *     parseable-but-invalid header is a HARD abort (`OrcaFlowException`),
    *     not a silent fresh start — it signals tampering or a mismatch. (An
    *     *unparseable* log is `loadDetailed() == Corrupt(reason)` → fresh run,
    *     but WARNED (logger + `emit(Step)`) since it's distinguishable from a
    *     genuinely absent log; that path is separate below.)
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
      store: ProgressStore,
      emit: OrcaEvent => Unit
  ): FlowSetup =
    given InStage = RuntimeInStage.token()
    given WorkspaceWrite = RuntimeInStage.workspaceToken()
    val log = LoggerFactory.getLogger("orca.flow")
    val startBranch = git.currentBranch()
    // Snapshot the log file before the stash, restore it if the stash
    // removed it — so an uncommitted/untracked log is still readable below.
    val snapshot = snapshotLog(store.path)
    val _ = git.ensureClean("orca: starting flow")
    restoreLogIfMissing(store.path, snapshot)
    // The protected set BOTH arms enforce: the always-protected floor
    // (`main`/`master`) plus the repo's ACTUAL detected default branch
    // (best-effort — `git.defaultBranch()` is read-only/cheap and already
    // collapses "no remote"/detection failure to `None` internally, so a
    // failed detection silently falls back to just the floor here, exactly
    // as it already does for the resume arm). Computed ONCE so the fresh arm
    // (via `FeatureBranch.resolve`) and the resume arm (via
    // `RecoveryCheck.validateHeader`) apply the identical policy from the
    // identical set — this is what makes "a protected name can never reach
    // `FlowSetup.featureBranch`" true from either arm.
    val protectedBranches =
      RecoveryCheck.alwaysProtected ++ git.defaultBranch().map(_.toLowerCase)
    store.loadDetailed() match
      case ProgressStore.LoadResult.Corrupt(reason) =>
        // The log file exists but didn't parse — a truncated/corrupted write,
        // not the normal "no log yet" case. We still start fresh (there's no
        // sane way to resume from unparseable data), but the user may have
        // expected a resume, so this must be LOUD, not silently
        // indistinguishable from a first run. `ctx` exists by the time `setup`
        // runs, so its `emit` is threaded in: an `OrcaEvent.Step` (there's no
        // Warning case — Step matches `GitTool`'s convention for a non-fatal
        // note) reaches BOTH the terminal renderer and any custom Interaction's
        // listeners (e.g. Slack), which a raw stderr line never would. The
        // logger keeps the DEBUG trace; we emit the Step INSTEAD of a stderr
        // line so a terminal user doesn't see it twice.
        log.warn(
          s"progress log at ${store.path} is corrupt ($reason); starting fresh"
        )
        emit(
          OrcaEvent.Step(
            s"progress log at ${store.path} is corrupt ($reason); " +
              "starting fresh — the previous run's stages will re-run"
          )
        )
        freshRun(
          args,
          agent,
          git,
          branchNaming,
          store,
          startBranch,
          protectedBranches,
          emit
        )
      case ProgressStore.LoadResult.Absent =>
        freshRun(
          args,
          agent,
          git,
          branchNaming,
          store,
          startBranch,
          protectedBranches,
          emit
        )
      case ProgressStore.LoadResult.Loaded(progressLog) =>
        val header = progressLog.header
        // Validate the untrusted header before any destructive action. The
        // protected set is the main/master floor plus the repo's ACTUAL default
        // branch (best-effort), so a tampered header naming e.g. `trunk` as a
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
        FlowSetup(store, featureBranch, header.startingBranch)

  /** Fresh run: resolve + create the branch, then commit the header so it is
    * the branch's first commit. Shared by the genuinely-absent-log case and the
    * corrupt-log case (which warns, then falls through to the same fresh start
    * — there is no sane way to resume from unparseable data). Needs BOTH
    * tokens: `InStage` because branch-name resolution may call the cheap model
    * (`BranchNamingStrategy.shortenPrompt`), `WorkspaceWrite` for the git
    * checkout/commit and header write.
    *
    * The resolved name is minted into a [[FeatureBranch]] before it ever
    * reaches git: a strategy/cheap-model reply that collides with a protected
    * branch (`main`, `master`, or the repo's detected default) is REFUSED, not
    * bound to. Refusal falls back to a deterministic
    * `BranchNamingStrategy.flowFallbackName(args.userPrompt)` (same prompt →
    * same fallback, so a resumed run still finds the branch) — loudly, via an
    * `OrcaEvent.Step` — rather than aborting the run: unattended/scheduled runs
    * must not flip between success and failure purely because the cheap model's
    * summary happened to phrase itself as "main" this time (mirrors
    * `shortenPrompt`'s existing "naming never blocks the flow" idiom).
    *
    * The (now protection-checked) name still isn't bound to git yet:
    * [[createFreshBranch]] applies the SAME "never silently adopt" policy to a
    * git-level `BranchAlreadyExists` collision — an unrelated pre-existing
    * branch (leftover from an earlier run, hand-created, or a slug collision
    * between two different prompts) must never be silently checked out and
    * carried into this run.
    */
  private def freshRun(
      args: OrcaArgs,
      agent: Agent[?],
      git: GitTool,
      branchNaming: Option[BranchNamingStrategy],
      store: ProgressStore,
      startBranch: String,
      protectedBranches: Set[String],
      emit: OrcaEvent => Unit
  )(using InStage, WorkspaceWrite): FlowSetup =
    val strategy =
      branchNaming.getOrElse(BranchNamingStrategy.shortenPrompt)
    val resolvedName = strategy.resolve(args.userPrompt, agent)
    // Resolved once, eagerly: a cheap pure hash + set lookup, shared by BOTH
    // fallback triggers below (a protected-name refusal and a git-level
    // `BranchAlreadyExists` collision use the exact same deterministic name).
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
    val branch = createFreshBranch(git, protectionChecked, fallback, emit)
    store.writeHeader(
      ProgressHeader(
        startingBranch = startBranch,
        branch = branch.value,
        promptHash = ProgressStore.hashPrompt(args.userPrompt)
      )
    )
    git.forceAdd(store.path)
    val _ = git.commit("orca: progress log")
    FlowSetup(store, branch, startBranch)

  /** The deterministic `flow-<hash>` fallback name for `userPrompt`
    * (`BranchNamingStrategy.flowFallbackName`), minted into a
    * [[FeatureBranch]]. Shared by both places `freshRun` needs a
    * guaranteed-safe rename: a protected-name refusal and a git-level
    * `BranchAlreadyExists` collision — one computation, so both triggers agree
    * on (and can compare against) the exact same name.
    *
    * Defensive re-check: a `flow-`-prefixed hash can't realistically collide
    * with a protected name, but re-validating rather than assuming keeps the
    * invariant airtight instead of merely believed.
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

  /** Create `candidate` fresh via `git.createBranch`, never falling back to
    * `checkoutOrCreate`'s old silent-adopt behaviour: a
    * `Left(BranchAlreadyExists)` means a branch by that name is ALREADY there
    * for some unrelated reason (an earlier run's leftover, a manually created
    * branch, a slug collision between two different prompts) — carrying that
    * branch's prior history into this run with zero signal is exactly the
    * hazard task 3.4 closes.
    *
    * Applies the SAME fallback-rename policy `freshRun` already uses for a
    * protected-name refusal: retry once, loudly (`Step`), with `fallback` (the
    * SAME deterministic name `freshRun` resolved once via [[resolveFallback]]).
    * If `candidate` IS ALREADY `fallback` (the protected-name fallback in
    * `freshRun` already rewrote it once), retrying would recreate the identical
    * name and collide again for certain — abort immediately instead of trying a
    * second time. Either way, a deterministic name colliding on a FRESH run (no
    * resume in play) means a previous run's branch is genuinely still there;
    * the user must decide what to do with it, not have orca guess again.
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

  /** Run a teardownSuccess leg best-effort: any `NonFatal` failure is caught
    * and debug-logged (never printed, never surfaced) so it cannot escape
    * teardown, trigger the failure path, or strand the user on a successful
    * run. `what` names the leg for the log line.
    */
  private def bestEffort(what: String)(op: => Unit): Unit =
    val log = LoggerFactory.getLogger("orca.flow")
    try op
    catch
      case NonFatal(e) =>
        log.debug(s"teardownSuccess: $what failed (cosmetic, swallowed)", e)

  /** Successful teardown (ADR 0018 §2.5): remove the progress-log file in a
    * final commit so a merged branch is clean, then hand off to
    * [[finishBranch]] for where HEAD lands — stay on the feature branch
    * (default), return to the starting branch (`returnToStartBranch`), or
    * delete a throwaway and return to start.
    *
    * Errors during log removal, the cleanup commit, or the branch handoff are
    * cosmetic on an already-successful run — every leg runs through
    * [[bestEffort]], so none of them can escape teardown, trigger the failure
    * path, or strand the user. A missing progress-log file
    * (`NoSuchFileException`, the ordinary "already removed" case) stays fully
    * silent rather than debug-logged; every other failure is debug-logged.
    */
  private[orca] def teardownSuccess(
      git: GitTool,
      setup: FlowSetup,
      returnToStartBranch: Boolean
  ): Unit =
    // Teardown is runtime code running outside any user stage, so it mints its
    // own `WorkspaceWrite` via `RuntimeInStage` — the runtime is the privileged
    // token constructor. No LLM call happens here, so `InStage` isn't needed.
    given WorkspaceWrite = RuntimeInStage.workspaceToken()
    try
      bestEffort("remove progress log"):
        try
          val _ = os.remove(setup.store.path)
        catch case _: java.nio.file.NoSuchFileException => ()
      // `add -A` in commit picks up the removal; NothingToCommit (a Left) means
      // it was never committed — harmless. A genuine commit failure is
      // cosmetic: the run already succeeded and the progress file is gone
      // from the tree.
      bestEffort("commit progress-log removal"):
        val _ = git.commit("orca: remove progress log")
    finally
      bestEffort("branch handoff"):
        finishBranch(git, setup, returnToStartBranch)

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
  )(using WorkspaceWrite): Unit =
    val throwaway =
      setup.featureBranch.value != setup.startBranch &&
        git
          .diffBranchExcludingOrca(setup.startBranch, setup.featureBranch.value)
          .isBlank
    if throwaway then
      // The start branch existed when this run began (it's either the repo's
      // HEAD at setup time or a prior run's recorded header) — a plain
      // `checkout` is enough; if it's gone mid-run that's genuinely
      // exceptional, not a case to silently paper over by creating it anew.
      git.checkout(setup.startBranch).orThrow
      git.deleteBranch(setup.featureBranch.value)
    else if returnToStartBranch then git.checkout(setup.startBranch).orThrow
    // else: stay on the feature branch (the default).

  /** Failure teardown (ADR 0018 §2.5): discard the failed stage's uncommitted
    * partial edits with `git reset --hard` (which restores the last committed
    * log), and stay on the feature branch so the next run resumes in place.
    */
  private[orca] def teardownFailure(git: GitTool): Unit =
    // Runtime teardown mints its own token, as in `teardownSuccess`.
    given WorkspaceWrite = RuntimeInStage.workspaceToken()
    git.resetHard()
