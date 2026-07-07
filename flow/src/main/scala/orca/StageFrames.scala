package orca

/** Per-run stage-identity bookkeeping shared by every [[FlowControl]]
  * implementation (production [[orca.runner.DefaultFlowContext]] and the test
  * doubles), so a test double can never drift from production semantics and
  * silently greenwash a nesting/resume test.
  *
  * A stack of frames — one per currently-open stage, plus a root frame for the
  * flow body — scopes occurrence counters hierarchically. Each frame carries
  * the path id of the stage that opened it and its own `name -> count` map for
  * the stages nested directly under it, so a stage's id is the parent frame's
  * path joined with `name#occurrence` (e.g. `outer#0/inner#0`) rather than a
  * flat per-run counter. Flat ids let a skipped (resumed) parent's vanished
  * nested bumps mis-key a later same-named stage; path ids keep siblings under
  * different parents structurally distinct.
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
    * for the new stage, and return its full path id. The bump happens against
    * the *parent* (current) frame exactly once per call, so a skipped stage
    * that is entered-then-immediately-exited still consumes its parent-frame
    * slot — keeping later same-named siblings stable across resume.
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
