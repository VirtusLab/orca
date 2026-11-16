package orca.testkit

import orca.OrcaDir
import orca.effect.{FileSystem, Processes}
import orca.events.OrcaListener
import orca.json.JsonFile
import orca.tools.{OsGitTool, Worktrees}

/** Ambient capabilities, and the effectful helpers built from them, for tests.
  */
object Effects:
  val fs: FileSystem = FileSystem.ambient
  val procs: Processes = Processes.ambient
  val orcaDir: OrcaDir = OrcaDir(fs)
  val jsonFile: JsonFile = JsonFile(fs)
  val worktrees: Worktrees = Worktrees(procs, fs)

  def gitTool(
      workDir: os.Path,
      events: OrcaListener = OrcaListener.noop
  ): OsGitTool =
    OsGitTool(workDir, procs, fs, events)
