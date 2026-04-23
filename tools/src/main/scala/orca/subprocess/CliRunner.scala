package orca.subprocess

case class CliResult(exitCode: Int, stdout: String, stderr: String)

trait CliProcess:
  def sendSigInt(): Unit
  def isAlive: Boolean
  def waitForExit(): Int

/** A spawned process whose stdin / stdout / stderr are connected to pipes
  * the caller controls, rather than inherited from the parent TTY. The
  * driver writes NDJSON to `writeLine` and consumes responses as they
  * arrive from `stdoutLines`. `closeStdin` signals end-of-input — claude
  * treats that as "no more user turns" and emits a final `result`.
  *
  * Reads on `stdoutLines` / `stderrLines` block until a line is available
  * or the stream closes; they're safe to iterate from a dedicated thread
  * (see `orca.tools.claude.ClaudeConversation`).
  */
trait PipedCliProcess extends CliProcess:
  def writeLine(json: String): Unit
  def closeStdin(): Unit
  def stdoutLines: Iterator[String]
  def stderrLines: Iterator[String]

trait CliRunner:
  def run(
      args: Seq[String],
      stdin: String = "",
      env: Map[String, String] = Map.empty,
      cwd: os.Path = os.pwd
  ): CliResult

  /** Spawn the command with inherited stdio for terminal handoff. Returns a
    * handle the caller can signal and await.
    */
  def spawn(
      args: Seq[String],
      env: Map[String, String] = Map.empty,
      cwd: os.Path = os.pwd
  ): CliProcess

  /** Spawn the command with pipes on stdin / stdout / stderr for
    * programmatic orchestration (stream-json, tool-approval, etc.).
    * See `PipedCliProcess` for the I/O surface.
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

  def spawn(
      args: Seq[String],
      env: Map[String, String],
      cwd: os.Path
  ): CliProcess =
    val sub = os
      .proc(args)
      .spawn(
        cwd = cwd,
        env = env,
        stdin = os.Inherit,
        stdout = os.Inherit,
        stderr = os.Inherit
      )
    new OsInheritedSubProcess(sub)

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

/** Shared lifecycle surface — both inherited and piped wrappers delegate
  * here to avoid re-implementing SIGINT, isAlive, waitForExit.
  */
private abstract class OsSubProcessBase(sub: os.SubProcess) extends CliProcess:
  def sendSigInt(): Unit =
    val _ = os
      .proc("kill", "-INT", sub.wrapped.pid.toString)
      .call(check = false)

  def isAlive: Boolean = sub.isAlive()

  def waitForExit(): Int =
    val _ = sub.join()
    sub.exitCode()

private final class OsInheritedSubProcess(sub: os.SubProcess)
    extends OsSubProcessBase(sub)

private final class OsPipedSubProcess(sub: os.SubProcess)
    extends OsSubProcessBase(sub)
    with PipedCliProcess:

  def writeLine(json: String): Unit =
    sub.stdin.writeLine(json)
    sub.stdin.flush()

  def closeStdin(): Unit = sub.stdin.close()

  def stdoutLines: Iterator[String] = sub.stdout.lines().iterator

  def stderrLines: Iterator[String] = sub.stderr.lines().iterator
