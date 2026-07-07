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
  * advertise cross-thread sharing. (Epic 7.1 adds the runtime owner-thread
  * assert; when it lands it must cover `enterStage`/`exitStage` here alongside
  * the `next*` methods it already names.)
  */
private[orca] trait StageFrames:

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
    val parent = frames.head
    val segment = s"$name#${parent.next(name)}"
    val id = if parent.path.isEmpty then segment else s"${parent.path}/$segment"
    frames = new Frame(id) :: frames
    id

  /** Pop the current stage frame. Balanced with [[enterStage]] by `stage`'s
    * try/finally; never pops the root frame in correct use.
    */
  def exitStage(): Unit =
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
    val n = sessionCounts.getOrElse(name, 0)
    sessionCounts = sessionCounts.updated(name, n + 1)
    n
