package orca.subprocess

trait CliProcess:
  def sendSigInt(): Unit
  def isAlive: Boolean
  def waitForExit(): Int

  /** Forcibly terminate this process (SIGKILL for the OS-backed process; close
    * the pipes for fakes), the guaranteed backstop a [[StreamSource]] uses
    * after a graceful `sendSigInt` so a reader blocked on stdout always
    * unblocks. Must tolerate calls from any thread and more than once; on the
    * normal path the process has already exited, making this a no-op.
    */
  def destroyForcibly(): Unit

  /** Forcibly terminate this process AND any descendants. Default = just this
    * process; override where a launch wrapper (e.g. `ollama launch opencode`)
    * forks the real process, which inherits the stdout/stderr pipe fds: a
    * single-PID kill would orphan it and leave those write-ends open, so a
    * reader blocked on the pipe would never see EOF. Same call-anywhere /
    * idempotent contract as [[destroyForcibly]].
    */
  def destroyForciblyTree(): Unit = destroyForcibly()

/** A spawned process whose stdin / stdout / stderr are connected to pipes the
  * caller controls. The backend writes the opening user turn (or any further
  * input) via `writeLine` and consumes responses as they arrive from
  * `stdoutLines`. `closeStdin` signals end-of-input — the agent CLI then emits
  * its final result and exits. Both backends close stdin once the opening turn
  * has been written: claude (with `--input-format stream-json`) waits for EOF
  * before flushing the final `result`; codex `exec --json` reads its prompt
  * argv-side and ignores stdin entirely once the spawn settles.
  *
  * Reads on `stdoutLines` / `stderrLines` block until a line is available or
  * the stream closes. Each iterator must be consumed by a single thread;
  * internal buffering of pending lines is not thread-safe across readers.
  * Implementations memoise the iterator so repeated property accesses return
  * the same underlying stream.
  */
trait PipedCliProcess extends CliProcess:
  def writeLine(line: String): Unit
  def closeStdin(): Unit
  def stdoutLines: Iterator[String]
  def stderrLines: Iterator[String]

  /** Non-blocking exit probe. `None` while still running; `Some(code)` once the
    * process has exited. The reader fork uses this to tell a clean EOF from a
    * crash.
    */
  def tryExitCode: Option[Int]
