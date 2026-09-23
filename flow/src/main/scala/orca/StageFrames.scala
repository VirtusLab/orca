package orca

import orca.agents.SessionKey
import orca.gitref.CommitHash

/** Where a turn sits in this run's use of one durable conversation. Minted by
  * [[StageFrames.claimTurn]].
  */
private[orca] enum SessionTurn:
  /** The run has not driven this conversation before. Only here can the
    * conversation's memory predate the run — and so disagree with a working
    * tree the run started from.
    */
  case First

  /** The run has already driven this conversation, so everything it remembers
    * doing, it did here.
    */
  case Later

/** Per-run bookkeeping shared by every [[FlowControl]] implementation
  * (production [[orca.runner.DefaultFlowContext]] and the test doubles), so a
  * test double can't drift from production semantics and greenwash a
  * nesting/resume test: stage identity and baselines, the say-once session-key
  * claim, and which durable conversations this run has already driven.
  * Canonical description of the frame-stack protocol; see ADR 0018 §2.1 for the
  * design rationale.
  *
  * '''Mechanism.''' A stack of frames — one per currently-open stage, plus a
  * root frame ([[StagePath.FlowBody]]) for the flow body — scopes occurrence
  * counters hierarchically. Each frame stores its own path and a `name ->
  * count` map for the stages nested directly under it, so [[withStage]] builds
  * a child's path with [[StagePath.child]]. `withStage` pushes the child frame
  * for the duration of its body, so the head is always the current scope.
  *
  * A frame also records the commit its stage started from, read back as
  * [[stageBaseCommit]] (ADR 0018 §2.1).
  *
  * '''Invariants.'''
  *   - '''Exactly-once bump.''' `withStage` bumps the parent's occurrence
  *     counter for `name` exactly once per stage attempt, before the resume
  *     decision — so the slot is consumed whether the body is skipped,
  *     completes, or throws. Later same-named siblings then see a stable
  *     occurrence index across resumes.
  *   - '''Structural unreachability.''' A skipped stage's body never runs, so
  *     its nested `stage(...)` calls never `withStage` and no counter desyncs.
  *     A flat id scheme could not offer this: a skipped parent's vanished
  *     nested bumps would let a later same-named stage recompute a nested id
  *     and misattribute a stale or wrong-typed record.
  *
  * Thread-affine: reached only through [[FlowControl]], single-threaded per
  * top-level `flow(...)` (R12, ADR 0018 §2.2), so plain vars state the real
  * invariant. `ownerThread` (captured at construction) is asserted on every
  * door that touches them, so a stray call from an `ox.fork` — always a fresh
  * thread on the pinned ox 1.0.8 — throws instead of silently corrupting the
  * frame stack / counters. Ox runs a `supervised:` block's own body on a fresh
  * fork too, so `stage(...)` from the direct body of a user-opened nested scope
  * is rejected just like an explicit `fork`. Production is unaffected:
  * `runFlow` constructs the context inside the same `supervised:` body that
  * runs the flow, so owner and body thread coincide.
  *
  * '''This is the only enforcement of R12 for user flow scripts.''' The
  * capture/separation checking enforcement (ADR 0018 §6) catches a
  * fork-boundary violation at compile time, but only in files opting into the
  * `captureChecking`/`separationChecking` imports — today just
  * `orca.review.ReviewLoop`, no user `.sc` script. It's also strictly stronger:
  * a capture check can't see a leak via mutable storage (a fork reading a
  * `FlowControl` out of a `var`/global a stage stashed it in); this runtime
  * assert can.
  */
private[orca] trait StageFrames:
  private val ownerThread: Thread = Thread.currentThread()

  /** Throws when called off `ownerThread` (R12 — see the trait scaladoc). Also
    * called by `FlowSession`'s run doors, so durable runs — not just
    * stage/session minting — refuse from a fork at runtime.
    */
  private[orca] def assertOwnerThread(what: String): Unit =
    if Thread.currentThread() ne ownerThread then
      throw new OrcaFlowException(
        s"$what called from a fork — forks get FlowContext only (ADR 0018 R12)"
      )

  /** One open stage's scope: its path (the parent of stages nested in it), the
    * commit the stage started from, and the per-name occurrence counters for
    * stages nested directly beneath it.
    */
  private final class Frame(
      val path: StagePath,
      val baseCommit: Option[CommitHash]
  ):
    private var counts: Map[String, Int] = Map.empty
    def next(name: String): Int =
      val n = counts.getOrElse(name, 0)
      counts = counts.updated(name, n + 1)
      n

  // The flow body's frame; it is never popped.
  private var frames: List[Frame] =
    List(new Frame(path = StagePath.FlowBody, baseCommit = None))

  /** Run `f` with a stage named `name` open, passing its path: bump the current
    * frame's occurrence counter for `name` and push a child frame recording
    * `baseCommit`, popped when `f` returns or throws. Called exactly once per
    * stage attempt — see the class doc's "Exactly-once bump" invariant.
    */
  private[orca] def withStage[R](
      name: String,
      baseCommit: Option[CommitHash]
  )(f: StagePath.Stage => R): R =
    assertOwnerThread("stage(...)")
    val enclosing = frames
    val path = enclosing.head.path.child(name, enclosing.head.next(name))
    frames = new Frame(path = path, baseCommit = baseCommit) :: enclosing
    try f(path)
    finally frames = enclosing

  def stageBaseCommit: Option[CommitHash] = frames.head.baseCommit

  /** Throws unless no stage is open: `what` runs stages of its own and belongs
    * at the flow body's top level, whatever helper it is called through.
    */
  private[orca] def assertAtFlowBody(what: String): Unit =
    assertOwnerThread(what)
    frames.head.path match
      case StagePath.FlowBody => ()
      case stage: StagePath.Stage =>
        throw new OrcaFlowException(
          s"$what called inside stage '${stage.display}' — call it at the flow " +
            "body's top level, outside every stage."
        )

  // The stage half of a key already scopes it, so one flat set covers the run.
  private var claimedSessionKeys: Set[SessionKey] = Set.empty

  /** Key a session named `name` to the stage currently open — the flow body
    * when there is none — and record the mint, rejecting a second mint of the
    * same name in the same stage.
    *
    * The only door that MINTS a [[SessionKey]], so a minted key is always
    * scoped to where the call sits and always claimed. `SessionRecord.key`
    * rebuilds one from persisted halves, and `ManifestSession.minted` reads one
    * back from the attempt manifest.
    *
    * For a mint inside a stage the check is sound rather than best-effort,
    * because a stage body is all-or-nothing: two mints of one name in one stage
    * either both execute or neither does, so the claim set sees both whenever
    * they could collide. The flow body carries no such guarantee — two mutually
    * exclusive mints of one name there are never both claimed — which costs the
    * warning, not correctness: each still resolves to the single record stored
    * at that key.
    *
    * A resumed run starts with nothing claimed, so re-minting a key the store
    * already holds is the reuse path.
    */
  private[orca] def claimSessionKey(name: String): SessionKey =
    assertOwnerThread("agent.session(...)")
    val frame = frames.head
    val key = SessionKey(name = name, stage = frame.path)
    if claimedSessionKeys.contains(key) then
      val where = frame.path match
        case StagePath.FlowBody     => "the flow body"
        case stage: StagePath.Stage => s"stage '${stage.display}'"
      throw new OrcaFlowException(
        s"agent.session(...) minted '$name' twice in $where — both handles " +
          "would drive one conversation. Give each its own `stage(...)`, or " +
          "rename one of them."
      )
    claimedSessionKeys = claimedSessionKeys + key
    key

  // A session id is unique across the run, so one flat set covers it as the
  // key set above covers keys.
  private var drivenSessions: Set[String] = Set.empty

  /** Claim `sessionId`'s next turn: [[SessionTurn.First]] exactly once per
    * conversation per run, [[SessionTurn.Later]] after that.
    *
    * Claimed on every turn, whatever the turn then does with the answer — a
    * conversation opened by this run's own first turn must not read as first
    * again on its second.
    */
  private[orca] def claimTurn(sessionId: String): SessionTurn =
    assertOwnerThread("session.run(...)")
    if drivenSessions.contains(sessionId) then SessionTurn.Later
    else
      drivenSessions = drivenSessions + sessionId
      SessionTurn.First
