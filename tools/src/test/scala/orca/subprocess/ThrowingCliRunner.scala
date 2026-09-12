package orca.subprocess

/** A `CliRunner` that throws for the commands `failWhen` selects — as `os.proc`
  * does when the binary is not on the PATH — and answers `otherwise` for the
  * rest. For tests of code that has to survive a missing CLI.
  */
class ThrowingCliRunner(
    failWhen: Seq[String] => Boolean,
    otherwise: CliResult = CliResult(0, "", "")
) extends CliRunner:
  def run(
      args: Seq[String],
      stdin: String,
      env: Map[String, String],
      cwd: os.Path
  ): CliResult =
    if failWhen(args) then
      throw new java.io.IOException(
        s"Cannot run program \"${args.headOption.getOrElse("")}\""
      )
    else otherwise

  def spawnPiped(
      args: Seq[String],
      env: Map[String, String],
      cwd: os.Path,
      pipeStderr: Boolean
  ): PipedCliProcess =
    throw new UnsupportedOperationException(
      "ThrowingCliRunner does not support spawnPiped"
    )
