package orca.subprocess

trait CliRunner:
  def run(
      args: Seq[String],
      stdin: String = "",
      env: Map[String, String] = Map.empty,
      cwd: os.Path = os.pwd
  ): CliResult

  /** Spawn the command with pipes on stdin / stdout / stderr for programmatic
    * orchestration (stream-json, tool-approval, etc.); see [[PipedCliProcess]]
    * for the I/O surface.
    *
    * `pipeStderr = false` (default) inherits the child's stderr to the parent's
    * terminal — needed for chatty CLIs whose stderr can fill the pipe buffer
    * faster than the driver drains it (claude with `--verbose`). Set `true`
    * when the driver wants stderr lines as `ConversationEvent.Error`s and the
    * child's stderr volume is bounded enough that a 64KB pipe is safe.
    */
  def spawnPiped(
      args: Seq[String],
      env: Map[String, String] = Map.empty,
      cwd: os.Path = os.pwd,
      pipeStderr: Boolean = false
  ): PipedCliProcess
