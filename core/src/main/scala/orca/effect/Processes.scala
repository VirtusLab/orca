package orca.effect

import language.experimental.captureChecking

import caps.assumeSafe
import orca.subprocess.{
  CappedResult,
  CliResult,
  OsPipedSubProcess,
  PipedCliProcess,
  QuietProc
}

/** Authority to start child processes. Every child's stderr is captured, never
  * inherited.
  */
@assumeSafe
final class Processes private () extends caps.SharedCapability:

  /** Runs `args` to completion; a non-zero exit is returned, not thrown. `env`
    * is added to the inherited environment.
    */
  def call(
      args: Seq[String],
      cwd: os.Path,
      env: Map[String, String] = Map.empty,
      stdin: String = ""
  ): CliResult =
    val result = QuietProc.call(args, cwd = cwd, env = env, stdin = stdin)
    Processes.log.debug(
      "exit {}: {}",
      result.exitCode,
      args.headOption.getOrElse("?")
    )
    CliResult(result.exitCode, result.out.text(), result.err.text())

  /** [[call]], keeping at most `maxBytes` of each of stdout and stderr. */
  def callCapped(
      args: Seq[String],
      maxBytes: Int,
      cwd: os.Path,
      env: Map[String, String] = Map.empty
  ): CappedResult =
    QuietProc.callCapped(args, maxBytes, cwd = cwd, env = env)

  /** Starts `args` with piped stdin, stdout and stderr. The caller must drain
    * both `stdoutLines` and `stderrLines` until EOF: a child blocks once either
    * pipe's buffer is full.
    */
  def spawnPiped(
      args: Seq[String],
      cwd: os.Path,
      env: Map[String, String] = Map.empty
  ): PipedCliProcess =
    OsPipedSubProcess.spawn(args, env = env, cwd = cwd)

object Processes:
  private val log = org.slf4j.LoggerFactory.getLogger("orca.proc")

  /** For wiring and tests only: safe code cannot call this. */
  private[orca] def ambient: Processes = new Processes()
