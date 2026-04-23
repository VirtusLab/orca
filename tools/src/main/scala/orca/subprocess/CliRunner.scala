package orca.subprocess

case class CliResult(exitCode: Int, stdout: String, stderr: String)

trait CliProcess:
  def sendSigInt(): Unit
  def isAlive: Boolean
  def waitForExit(): Int

/** A spawned process whose stdin / stdout / stderr are connected to pipes
  * the caller controls. The driver writes a line at a time to `writeLine`
  * and consumes responses as they arrive from `stdoutLines`. `closeStdin`
  * signals end-of-input — claude treats that as "no more user turns" and
  * emits a final `result`.
  *
  * Reads on `stdoutLines` / `stderrLines` block until a line is available
  * or the stream closes. Each iterator must be consumed by a single
  * thread (see `orca.tools.claude.ClaudeConversation`); internal
  * buffering of pending lines is not thread-safe across readers.
  * Implementations memoise the iterator so repeated property accesses
  * return the same underlying stream.
  */
trait PipedCliProcess extends CliProcess:
  def writeLine(line: String): Unit
  def closeStdin(): Unit
  def stdoutLines: Iterator[String]
  def stderrLines: Iterator[String]

  /** Non-blocking exit probe. `None` while still running; `Some(code)`
    * once the process has exited. The reader fork uses this to tell a
    * clean EOF from a crash.
    */
  def tryExitCode: Option[Int]

trait CliRunner:
  def run(
      args: Seq[String],
      stdin: String = "",
      env: Map[String, String] = Map.empty,
      cwd: os.Path = os.pwd
  ): CliResult

  /** Spawn the command with pipes on stdin / stdout / stderr for
    * programmatic orchestration (stream-json, tool-approval, etc.).
    * See [[PipedCliProcess]] for the I/O surface.
    */
  def spawnPiped(
      args: Seq[String],
      env: Map[String, String] = Map.empty,
      cwd: os.Path = os.pwd
  ): PipedCliProcess

/** Runs external commands via os-lib. `check = false` is intentional — callers
  * inspect `exitCode` and `stderr` rather than handling thrown exceptions,
  * since non-zero exits from tools like `claude -p` carry actionable
  * information.
  */
object OsProcCliRunner extends CliRunner:
  def run(
      args: Seq[String],
      stdin: String,
      env: Map[String, String],
      cwd: os.Path
  ): CliResult =
    val result = os
      .proc(args)
      .call(
        cwd = cwd,
        env = env,
        stdin = stdin,
        check = false
      )
    CliResult(result.exitCode, result.out.text(), result.err.text())

  def spawnPiped(
      args: Seq[String],
      env: Map[String, String],
      cwd: os.Path
  ): PipedCliProcess =
    val sub = os
      .proc(args)
      .spawn(
        cwd = cwd,
        env = env,
        stdin = os.Pipe,
        stdout = os.Pipe,
        stderr = os.Pipe
      )
    new OsPipedSubProcess(sub)

private final class OsPipedSubProcess(sub: os.SubProcess)
    extends PipedCliProcess:

  // Memoised so repeated calls return the same iterator, avoiding a
  // second `BufferedReader` leak against the pipe.
  private lazy val stdoutIterator: Iterator[String] = sub.stdout.lines().iterator
  private lazy val stderrIterator: Iterator[String] = sub.stderr.lines().iterator

  def sendSigInt(): Unit =
    val _ = os
      .proc("kill", "-INT", sub.wrapped.pid.toString)
      .call(check = false)

  def isAlive: Boolean = sub.isAlive()

  def waitForExit(): Int =
    val _ = sub.join()
    sub.exitCode()

  def writeLine(line: String): Unit =
    sub.stdin.writeLine(line)
    sub.stdin.flush()

  def closeStdin(): Unit = sub.stdin.close()

  def stdoutLines: Iterator[String] = stdoutIterator

  def stderrLines: Iterator[String] = stderrIterator

  def tryExitCode: Option[Int] =
    if sub.isAlive() then None else Some(sub.exitCode())
