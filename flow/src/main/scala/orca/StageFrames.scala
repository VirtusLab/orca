package orca

/** Per-run stage-identity bookkeeping shared by every [[FlowControl]]
  * implementation (production [[orca.runner.DefaultFlowContext]] and the test
  * doubles), so a test double can never drift from production semantics and
  * silently greenwash a nesting/resume test. This is the canonical description
  * of the frame-stack protocol — [[FlowControl]] and `stage` in Flow.scala
  * point back here rather than repeating it; see ADR 0018 §2.1 for the design
  * rationale.
  *
  * '''Mechanism.''' A stack of frames — one per currently-open stage, plus a
  * root frame (path `""`) for the flow body — scopes occurrence counters
  * hierarchically. Each frame stores its own full path id (denormalized, rather
  * than just a parent pointer plus a segment) and a `name -> count` map for the
  * stages nested directly under it, so [[enterStage]] builds a child's id by
  * string-joining `name#occurrence` onto the already-materialized parent path —
  * no walk up the stack needed. `enterStage` pushes the child frame;
  * [[exitStage]] pops it; the stack's head is always the current scope.
  *
  * '''Invariants.'''
  *   - '''Exactly-once bump.''' `enterStage` bumps the parent frame's
  *     occurrence counter for `name` exactly once per stage attempt, before the
  *     resume decision is made — so the slot is consumed whether the body is
  *     skipped, runs to completion, or throws (`stage` pops the frame in a
  *     `finally`, covering all three outcomes). Later same-named siblings
  *     therefore see a stable occurrence index across resumes.
  *   - '''Structural unreachability.''' A skipped (resumed) stage's body never
  *     runs, so its nested `stage(...)` calls never fire and never call
  *     `enterStage` — their frames are never opened and no counter desyncs. A
  *     flat, un-nested id scheme could not offer this: a skipped parent's
  *     vanished nested bumps would let a later same-named stage recompute the
  *     nested stage's id and misattribute a stale or wrong-typed record.
  *   - '''Opaque paths.''' The `#`/`/`-joined path id is only ever compared for
  *     exact equality, never parsed or reconstructed from its parts.
  *
  * Thread-affine: reached only through [[FlowControl]], which is
  * single-threaded per top-level `flow(...)` (R12, ADR 0018 §2.2) — stages,
  * `enterStage`, `exitStage` and `session(...)` calls never run concurrently,
  * so plain vars state the real invariant where a concurrent map would falsely
  * advertise cross-thread sharing. `ownerThread` (captured when the concrete
  * class mixing this trait in is constructed) is asserted against
  * `Thread.currentThread()` on [[enterStage]], [[exitStage]], and
  * [[nextSessionOccurrence]], so a stray call from an `ox.fork` — always a
  * fresh thread, verified for the pinned ox 1.0.5 — throws immediately instead
  * of silently corrupting the frame stack / occurrence counters. Every
  * `StageFrames` implementation (production `DefaultFlowContext`, every test
  * double) gets this for free, per this trait's own purpose above. Note that Ox
  * runs a `supervised:` block's own body on a fresh fork as well, so
  * `stage(...)` from the direct body of a user-opened nested scope is rejected
  * just like an explicit `fork` — the same boundary CC's separation checking
  * draws (that body is a fork closure capturing the exclusive capability).
  * Production is unaffected: `runFlow` constructs the context inside the same
  * `supervised:` body that runs the flow, so owner and body thread coincide.
  *
  * '''This is the only enforcement of R12 for user flow scripts.''' The
  * capture/separation checking enforcement (ADR 0018 §6) catches a fork
  * boundary violation at compile time, but only in files that opt into the
  * `captureChecking`/ `separationChecking` language imports — today that's
  * exactly one internal call site (`orca.review.ReviewLoop`'s reviewer
  * fan-out); no example or user `.sc` script carries them. It's also strictly
  * stronger than CC regardless: a capture check can't see a leak via mutable
  * storage (a fork reading a `FlowControl` out of a `var`/global a stage
  * stashed it in), only this runtime assert can.
  */
private[orca] trait StageFrames:
  private val ownerThread: Thread = Thread.currentThread()

  /** Throws with the R12 message when called off `ownerThread` — see the trait
    * scaladoc's "only enforcement of R12 for user flow scripts" note.
    */
  private def assertOwnerThread(): Unit =
    if Thread.currentThread() ne ownerThread then
      throw new OrcaFlowException(
        "stage(...)/session(...) called from a fork — forks get FlowContext only (ADR 0018 R12)"
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

  // The root frame (path "") is the flow body; it is never popped. `enterStage`
  // pushes, `exitStage` pops, so the head is always the current scope.
  private var frames: List[Frame] = List(new Frame(""))

  /** Bump the current frame's occurrence counter for `name`, push a child frame
    * for the new stage, and return its full path id. See the class doc's
    * "Exactly-once bump" and "Structural unreachability" invariants for why
    * this must be called exactly once per stage attempt.
    */
  def enterStage(name: String): String =
    assertOwnerThread()
    val parent = frames.head
    val segment = s"$name#${parent.next(name)}"
    val id = if parent.path.isEmpty then segment else s"${parent.path}/$segment"
    frames = new Frame(id) :: frames
    id

  /** Pop the current stage frame. Balanced with [[enterStage]] by `stage`'s
    * try/finally; never pops the root frame in correct use.
    */
  def exitStage(): Unit =
    assertOwnerThread()
    frames = frames.tail

  /** True when at least one stage frame is open — i.e. execution is inside a
    * stage body. Used to gate `agent.session(...)` to the flow-body top level.
    */
  def inStage: Boolean = frames.tail.nonEmpty

  // Session occurrences are counted flat, independent of the stage frames:
  // `agent.session(...)` is required to be called outside any stage (see
  // `FlowControl.inStage` / `Session.session`), so it always mints against the
  // root scope. Keyed per-name, mirroring a frame's stage counter.
  private var sessionCounts: Map[String, Int] = Map.empty
  def nextSessionOccurrence(name: String): Int =
    assertOwnerThread()
    val n = sessionCounts.getOrElse(name, 0)
    sessionCounts = sessionCounts.updated(name, n + 1)
    n
