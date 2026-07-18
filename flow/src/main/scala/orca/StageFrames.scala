package orca

/** Per-run stage-identity bookkeeping shared by every [[FlowControl]]
  * implementation (production [[orca.runner.DefaultFlowContext]] and the test
  * doubles), so a test double can't drift from production semantics and
  * greenwash a nesting/resume test. Canonical description of the frame-stack
  * protocol; see ADR 0018 §2.1 for the design rationale.
  *
  * '''Mechanism.''' A stack of frames — one per currently-open stage, plus a
  * root frame (path `""`) for the flow body — scopes occurrence counters
  * hierarchically. Each frame stores its own full path id and a `name -> count`
  * map for the stages nested directly under it, so [[enterStage]] builds a
  * child's id by joining `name#occurrence` onto the parent path. `enterStage`
  * pushes the child frame, [[exitStage]] pops it, and the head is always the
  * current scope.
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
  * [[enterStage]], [[exitStage]], and [[nextSessionOccurrence]], so a stray
  * call from an `ox.fork` — always a fresh thread on the pinned ox 1.0.5 —
  * throws instead of silently corrupting the frame stack / counters. Ox runs a
  * `supervised:` block's own body on a fresh fork too, so `stage(...)` from the
  * direct body of a user-opened nested scope is rejected just like an explicit
  * `fork`. Production is unaffected: `runFlow` constructs the context inside
  * the same `supervised:` body that runs the flow, so owner and body thread
  * coincide.
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

  /** One open stage's scope: its own path id (the prefix children join under)
    * and the per-name occurrence counters for stages nested directly beneath
    * it.
    */
  private final class Frame(val path: String):
    private var counts: Map[String, Int] = Map.empty
    def next(name: String): Int =
      val n = counts.getOrElse(name, 0)
      counts = counts.updated(name, n + 1)
      n

  // The root frame (path "") is the flow body; it is never popped.
  private var frames: List[Frame] = List(new Frame(""))

  /** Bump the current frame's occurrence counter for `name`, push a child
    * frame, and return its full path id. Must be called exactly once per stage
    * attempt — see the class doc's "Exactly-once bump" invariant.
    */
  def enterStage(name: String): String =
    assertOwnerThread("stage(...)")
    val parent = frames.head
    val segment = s"$name#${parent.next(name)}"
    val id = if parent.path.isEmpty then segment else s"${parent.path}/$segment"
    frames = new Frame(id) :: frames
    id

  /** Pop the current stage frame. Balanced with [[enterStage]] by `stage`'s
    * try/finally; never pops the root frame in correct use.
    */
  def exitStage(): Unit =
    assertOwnerThread("stage(...)")
    frames = frames.tail

  /** True when at least one stage frame is open — i.e. execution is inside a
    * stage body. Used to gate `agent.session(...)` to the flow-body top level.
    */
  def inStage: Boolean = frames.tail.nonEmpty

  // Session occurrences are counted flat: `agent.session(...)` must be called
  // outside any stage, so it always mints against the root scope. Keyed
  // per-name, mirroring a frame's stage counter.
  private var sessionCounts: Map[String, Int] = Map.empty
  def nextSessionOccurrence(name: String): Int =
    assertOwnerThread("agent.session(...)")
    val n = sessionCounts.getOrElse(name, 0)
    sessionCounts = sessionCounts.updated(name, n + 1)
    n
