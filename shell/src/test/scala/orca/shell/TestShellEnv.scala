package orca.shell

import orca.ConfigHome
import orca.testkit.TempDirs

/** A [[ShellEnv]] over `workDir`, with its own temp config and cache homes and
  * no environment variables.
  */
object TestShellEnv:
  def apply(workDir: os.Path = TempDirs.dir()): ShellEnv =
    val home = TempDirs.dir()
    ShellEnv(
      workDir = workDir,
      configHome = ConfigHome(home / "config" / "orca"),
      cacheHome = home / "cache",
      vars = Map.empty.get
    )
