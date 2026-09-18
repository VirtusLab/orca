package orca

import orca.agents.SessionKey

/** Per-run stage-identity and stage-baseline bookkeeping shared by every
  * [[FlowControl]] implementation (production
  * [[orca.runner.DefaultFlowContext]] and the test doubles), so a test double
  * can't drift from production semantics and greenwash a nesting/resume test.
  * Canonical description of the frame-stack protocol; see ADR 0018 §2.1 for the
  * design rationale.
  *
  * '''Mechanism.''' A stack of frames — one per currently-open stage, plus a
  * root frame (path `""`) for the flow body — scopes occurrence counters
  * hierarchically. Each frame stores its own full path id and a `name -> count`
  * map for the stages nested directly under it, so [[enterStage]] builds a
  * child's id by joining `name#occurrence` onto the parent path. `enterStage`
  * pushes the child frame, [[exitStage]] pops it, and the head is always the
  * current scope.
  *
  * A frame also records the commit its stage started from, read back as
  * [[stageBaseCommit]] (ADR 0018 §2.1).
  *
  * '''Invariants.'''
  *   - '''Exactly-once bump.''' `enterStage` bumps the parent's occurrence
  *     counter for `name` exactly once per stage attempt, before the resume
  *     decision — so the slot is consumed whether the body is skipped,
  *     completes, or throws (`stage` pops in a `finally`). Later same-named
  *     siblings then see a stable occurrence index across resumes.
  *   - '''Structural unreachability.''' A skipped stage's body never runs, so
  *     its nested `stage(...)` calls never `enterStage` and no counter desyncs.
  *     A flat id scheme could not offer this: a skipped parent's vanished
  *     nested bumps would let a later same-named stage recompute a nested id
  *     and misattribute a stale or wrong-typed record.
  *   - '''Opaque paths.''' The `#`/`/`-joined path id is only ever compared for
  *     exact equality, never parsed or reconstructed.
  *
  * Thread-affine: reached only through [[FlowControl]], single-threaded per
  * top-level `flow(...)` (R12, ADR 0018 §2.2), so plain vars state the real
  * invariant. `ownerThread` (captured at construction) is asserted on
  * [[enterStage]], [[exitStage]], and [[claimSessionKey]], so a stray call from
  * an `ox.fork` — always a fresh thread on the pinned ox 1.0.5 — throws instead
  * of silently corrupting the frame stack / counters. Ox runs a `supervised:`
  * block's own body on a fresh fork too, so `stage(...)` from the direct body
  * of a user-opened nested scope is rejected just like an explicit `fork`.
  * Production is unaffected: `runFlow` constructs the context inside the same
  * `supervised:` body that runs the flow, so owner and body thread coincide.
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

  /** One open stage's scope: its own path id (the prefix children join under),
    * the commit the stage started from, and the per-name occurrence counters
    * for stages nested directly beneath it.
    */
  private final class Frame(val path: String, val baseCommit: Option[String]):
    private var counts: Map[String, Int] = Map.empty
    def peek(name: String): Int = counts.getOrElse(name, 0)
    def next(name: String): Int =
      val n = peek(name)
      counts = counts.updated(name, n + 1)
      n
    def childId(name: String, occurrence: Int): String =
      val segment = s"$name#$occurrence"
      if path.isEmpty then segment else s"$path/$segment"

  // The root frame (path "") is the flow body; it is never popped.
  private var frames: List[Frame] = List(new Frame("", None))

  /** Bump the current frame's occurrence counter for `name`, push a child frame
    * recording `baseCommit`, and return its full path id. Must be called
    * exactly once per stage attempt — see the class doc's "Exactly-once bump"
    * invariant.
    */
  def enterStage(name: String, baseCommit: Option[String]): String =
    assertOwnerThread("stage(...)")
    val parent = frames.head
    val id = parent.childId(name, parent.next(name))
    frames = new Frame(id, baseCommit) :: frames
    id

  /** The id the next [[enterStage]] for `name` in the current scope would mint,
    * without minting it.
    */
  def peekStageId(name: String): String =
    assertOwnerThread("peekStageId(...)")
    val parent = frames.head
    parent.childId(name, parent.peek(name))

  def stageBaseCommit: Option[String] = frames.head.baseCommit

  /** Pop the current stage frame. Balanced with [[enterStage]] by `stage`'s
    * try/finally; never pops the root frame in correct use.
    */
  def exitStage(): Unit =
    assertOwnerThread("stage(...)")
    frames = frames.tail

  // Flat, not per-frame: a key names one conversation for the whole run,
  // wherever `agent.session(...)` mints it.
  private var claimedSessionKeys: Set[SessionKey] = Set.empty

  /** Record that this execution has minted `key`, rejecting a second mint of a
    * key already claimed. Only mints within one execution collide: a resumed
    * run starts with nothing claimed, so re-minting a key the log already holds
    * is the reuse path, not a duplicate.
    *
    * Only mints that actually run claim, so two colliding mints in different
    * stages collide on the fresh run that reaches both, and not on a resume
    * that replays one of those stages.
    */
  private[orca] def claimSessionKey(key: SessionKey): Unit =
    assertOwnerThread("agent.session(...)")
    if claimedSessionKeys.contains(key) then
      throw new OrcaFlowException(
        s"agent.session(...) minted '${key.label}' twice in this run — both " +
          "handles would drive one conversation. Give each call a detail " +
          "naming what it serves (the task, say), and a second session for " +
          "the same work a detail that says so ('task 3, pass 2')."
      )
    claimedSessionKeys = claimedSessionKeys + key
