package orca.backend

import orca.OrcaFlowException
import orca.subprocess.PipedCliProcess

import scala.util.control.NonFatal

/** The two-level spawn-and-build teardown every subprocess stream backend
  * (claude/codex/gemini/pi) needs, factored out so the resource-leak handling —
  * the part most dangerous to get subtly wrong — lives in one place.
  *
  * The lifecycle:
  *
  *   - `spawn` builds the argv (system-prompt files, MCP config, the
  *     fresh-vs-resume dispatch lookup) and launches the process. If it throws,
  *     the process never came up, so only `resources` are released.
  *   - `build` wraps the live process in a [[Conversation]] (writing the
  *     opening turn, closing stdin, etc.). If it throws after the spawn, the
  *     child is SIGINT-ed (so it doesn't linger) and the failure is rethrown as
  *     "Failed to open <sessionLabel> session".
  *   - `resources` are the session-scoped `AutoCloseable`s (the ask_user
  *     bundle, pi's temp files) the conversation takes ownership of on success
  *     — on the happy path they ride through the conversation's `onFinalize`,
  *     so this closes them (in reverse) only when spawn or build fails.
  *
  * `sessionLabel` is the backend's own descriptor for the failure message
  * ("claude stream-json", "codex", "gemini", "pi RPC") — deliberately not the
  * bare backend name, which is pinned by tests.
  */
private[orca] object SubprocessSpawn:

  /** Returns an `AutoCloseable` that best-effort deletes the given file when
    * closed. Shared by the backends (claude's MCP config, codex's schema temp
    * file) that write a per-call temp file and need it removed on both the
    * failure path (via `open`'s `resources`) and the success path (via the
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
