package orca.subprocess

import scala.jdk.CollectionConverters.given

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
