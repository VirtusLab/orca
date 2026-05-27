package orca.subprocess

import scala.jdk.CollectionConverters.given

case class CliResult(exitCode: Int, stdout: String, stderr: String)

trait CliProcess:
  def sendSigInt(): Unit
  def isAlive: Boolean
  def waitForExit(): Int

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

trait CliRunner:
  def run(
      args: Seq[String],
      stdin: String = "",
      env: Map[String, String] = Map.empty,
      cwd: os.Path = os.pwd
  ): CliResult

  /** Spawn the command with pipes on stdin / stdout / stderr for programmatic
    * orchestration (stream-json, tool-approval, etc.). See [[PipedCliProcess]]
    * for the I/O surface.
    *
    * `pipeStderr = false` (default) inherits the child's stderr to the parent's
    * terminal — appropriate for chatty CLIs whose stderr can fill the pipe
    * buffer faster than the driver drains it (claude with `--verbose`). Set to
    * `true` when the driver wants to see stderr lines as
    * `ConversationEvent.Error`s and the child's stderr volume is bounded enough
    * that a 64KB pipe is safe.
    */
  def spawnPiped(
      args: Seq[String],
      env: Map[String, String] = Map.empty,
      cwd: os.Path = os.pwd,
      pipeStderr: Boolean = false
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
    // os-lib 0.11.x defaults `stderr = Inherit` for `call(...)`. We
    // explicitly capture both pipes so subprocess output never
    // bypasses the renderer's StatusBar — see [[QuietProc]] for the
    // full rationale. `mergeErrIntoOut` would also work but losing the
    // stdout/stderr distinction would weaken the diagnostic in
    // OrcaFlowException messages.
    val result = os
      .proc(args)
      .call(
        cwd = cwd,
        env = env,
        stdin = stdin,
        stdout = os.Pipe,
        stderr = os.Pipe,
        check = false
      )
    CliResult(result.exitCode, result.out.text(), result.err.text())

  def spawnPiped(
      args: Seq[String],
      env: Map[String, String],
      cwd: os.Path,
      pipeStderr: Boolean
  ): PipedCliProcess =
    val sub = os
      .proc(args)
      .spawn(
        cwd = cwd,
        env = env,
        stdin = os.Pipe,
        stdout = os.Pipe,
        // Defaults to Inherit — piping risks a buffer-fill hang when the
        // child emits more stderr than the driver drains in time (claude
        // with --verbose is chatty). Bounded-stderr backends (codex)
        // opt into piping so the driver can surface stderr lines as
        // ConversationEvent.Errors.
        stderr = if pipeStderr then os.Pipe else os.Inherit
      )
    new OsPipedSubProcess(sub, pipeStderr)

private final class OsPipedSubProcess(
    sub: os.SubProcess,
    stderrPiped: Boolean
) extends PipedCliProcess:

  // Memoised so repeated calls return the same iterator, avoiding a
  // second `BufferedReader` leak against the pipe.
  //
  // CRITICAL: must read line-by-line as the child emits — NOT
  // `sub.stdout.lines()`, which dispatches to `geny.ByteData.lines()` and
  // reads the whole stream to EOF before returning a `Vector[String]`. That
  // version turns stream-json into a no-op until the subprocess exits, with
  // all events appearing in a single burst at the end. Using the underlying
  // `BufferedReader` (already wired by os-lib) gives a properly lazy
  // line-by-line iterator.
  private lazy val stdoutIterator: Iterator[String] =
    sub.stdout.buffered.lines().iterator().asScala
  // When stderr is inherited to the parent, expose an empty iterator so
  // the `PipedCliProcess` contract still holds without reading from a
  // nonexistent pipe; when piped, expose the actual stream.
  private lazy val stderrIterator: Iterator[String] =
    if stderrPiped then sub.stderr.buffered.lines().iterator().asScala
    else Iterator.empty

  def sendSigInt(): Unit =
    val _ = QuietProc.call(Seq("kill", "-INT", sub.wrapped.pid.toString))

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
