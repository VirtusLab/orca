package orca.review

import orca.OrcaDir
import orca.effect.{FileSystem, Processes}
import orca.safe.concurrency.resourceScope
import orca.subprocess.CliResult

/** Runs the format and lint gates' commands, and spills lint output where the
  * summariser can read it.
  */
private[orca] trait GateShell:
  /** `command` through `bash -c` in `workDir`, stderr merged into stdout so
    * neither stream reaches the terminal. A nonzero exit is returned, not
    * thrown.
    */
  def run(command: String, workDir: os.Path): CliResult

  /** `body` over a file under `workDir`'s `.orca/cache/` holding `text`,
    * removed after.
    */
  def withSpillFile[T](text: String, workDir: os.Path)(body: os.Path => T): T

private[orca] final class OsGateShell(procs: Processes, fs: FileSystem)
    extends GateShell:
  def run(command: String, workDir: os.Path): CliResult =
    procs.call(Seq("bash", "-c", "exec 2>&1\n" + command), workDir)

  def withSpillFile[T](text: String, workDir: os.Path)(
      body: os.Path => T
  ): T =
    val cacheDir = OrcaDir(fs).ensureCache(workDir)
    resourceScope:
      body(fs.tempFile(text, "lint-", ".txt", dir = Some(cacheDir)))
