package orca.subprocess

trait CliRunner:
  def run(
      args: Seq[String],
      stdin: String = "",
      env: Map[String, String] = Map.empty,
      cwd: os.Path
  ): CliResult

  /** Spawn the command with pipes on stdin / stdout / stderr for programmatic
    * orchestration (stream-json, tool-approval, etc.); see [[PipedCliProcess]]
    * for the I/O surface.
    *
    * The caller must keep draining both `stdoutLines` and `stderrLines` until
    * EOF: a child blocks once either pipe's buffer is full.
    */
  def spawnPiped(
      args: Seq[String],
      env: Map[String, String] = Map.empty,
      cwd: os.Path
  ): PipedCliProcess
