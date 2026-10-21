package orca.subprocess

import orca.effect.Processes

/** Runs external commands through `procs`. Non-zero exits are returned, not
  * thrown: callers inspect `exitCode` and `stderr`, since non-zero exits from
  * tools like `claude -p` carry actionable information.
  */
final class OsProcCliRunner(procs: Processes) extends CliRunner:

  def run(
      args: Seq[String],
      stdin: String,
      env: Map[String, String],
      cwd: os.Path
  ): CliResult =
    procs.call(args, cwd = cwd, env = env, stdin = stdin)

  def spawnPiped(
      args: Seq[String],
      env: Map[String, String],
      cwd: os.Path
  ): PipedCliProcess =
    procs.spawnPiped(args, cwd = cwd, env = env)
