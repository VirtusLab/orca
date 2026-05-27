package orca.runner.terminal

/** Mutable depth counter consulted by [[TerminalEventListener]] (the OrcaEvent
  * stage listener) and [[ConversationRenderer]] (the per-conversation
  * renderer). Each `StageStarted` event pushes the counter; each
  * `StageCompleted` pops it. The counter dictates how many leading spaces every
  * printed line gets — nested stages indent their content under the enclosing
  * stage marker.
  *
  * Not thread-safe on its own. The listener serialises push/pop under its own
  * lock and republishes [[contentIndent]] via a `@volatile` snapshot so the
  * renderer (running on another thread) can read it without acquiring the lock.
  */
private[terminal] class StageDepth:
  private var depth: Int = 0

  /** Increment after a `StageStarted` is rendered. Stage markers print *before*
    * the matching push (and after the matching pop on close), so opening and
    * closing markers align with the parent stage's content indent.
    */
  def push(): Unit = depth += 1

  /** Decrement before a `StageCompleted` is rendered. Clamped at zero so a
    * stray `pop` from a malformed event stream can't wrap into a giant indent.
    */
  def pop(): Unit = depth = math.max(0, depth - 1)

  /** Indent string for the current depth. Two spaces per level — tight enough
    * that deeply-nested flows don't march off the right edge, visible enough to
    * separate stages.
    */
  def contentIndent: String = "  " * depth
