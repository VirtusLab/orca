package orca.backend

import orca.OrcaFlowException
import orca.subprocess.PipedCliProcess

import scala.util.control.NonFatal

/** The two-level spawn-and-build teardown every subprocess stream backend
  * (claude/codex/gemini/pi) needs, factored out so resource-leak handling lives
  * in one place.
  *
  *   - `spawn` builds the argv and launches the process. If it throws, the
  *     process never came up, so only `resources` are released.
  *   - `build` wraps the live process in a [[Conversation]]. If it throws after
  *     the spawn, the child is SIGINT-ed and the failure is rethrown as "Failed
  *     to open <sessionLabel> session".
  *   - `resources` are the session-scoped `AutoCloseable`s the conversation
  *     takes ownership of on success — on the happy path they ride through the
  *     conversation's `onFinalize`, so this closes them (in reverse) only when
  *     spawn or build fails.
  *
  * `sessionLabel` is the backend's descriptor for the failure message —
  * deliberately not the bare backend name, which is pinned by tests.
  */
private[orca] object SubprocessSpawn:

  /** An `AutoCloseable` that best-effort deletes the given file when closed.
    * Used by backends that write a per-call temp file needing removal on both
    * the failure path (via `open`'s `resources`) and the success path (via the
    * conversation's `onFinalize`).
    */
  def deleteFileResource(path: os.Path): AutoCloseable =
    () => if os.exists(path) then os.remove(path): Unit

  def open[C](
      sessionLabel: String,
      resources: List[AutoCloseable]
  )(spawn: => PipedCliProcess)(build: PipedCliProcess => C): C =
    try
      val process = spawn
      try build(process)
      catch
        case e: Exception =>
          // SIGINT the process; the outer catch releases `resources`.
          process.sendSigInt()
          throw OrcaFlowException(
            s"Failed to open $sessionLabel session: ${e.getMessage}"
          )
    catch
      case e: Throwable =>
        resources.reverseIterator.foreach: r =>
          try r.close()
          catch case NonFatal(_) => ()
        throw e
