package orca.shell

import orca.{ConfigHome, XdgDirs}

/** The process environment the shell acts on: its working directory, the
  * user-global config home, the XDG cache home, and the environment variables.
  * Built once per process by [[ShellEnv.current]]; tests build one over temp
  * dirs. Child processes (flow runs, the editor) still inherit the real process
  * environment.
  */
private[shell] final case class ShellEnv(
    workDir: os.Path,
    configHome: ConfigHome,
    cacheHome: os.Path,
    vars: String => Option[String]
)

private[shell] object ShellEnv:
  def current(): ShellEnv =
    ShellEnv(
      workDir = os.pwd,
      configHome = ConfigHome.resolve(sys.env.get, os.home),
      cacheHome = XdgDirs.cacheHome(sys.env.get, os.home),
      vars = sys.env.get
    )
