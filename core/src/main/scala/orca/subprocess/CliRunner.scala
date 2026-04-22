package orca.subprocess

case class CliResult(exitCode: Int, stdout: String, stderr: String)

trait CliProcess:
  def sendSigInt(): Unit
  def isAlive: Boolean
  def waitForExit(): Int

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
    OsSubProcess(sub)

private final class OsSubProcess(sub: os.SubProcess) extends CliProcess:
  def sendSigInt(): Unit =
    val _ = os
      .proc("kill", "-INT", sub.wrapped.pid.toString)
      .call(check = false)

  def isAlive: Boolean = sub.isAlive()

  def waitForExit(): Int =
    val _ = sub.join()
    sub.exitCode()
