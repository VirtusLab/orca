package orca.shell

import orca.testkit.TempDirs

class TierTest extends munit.FunSuite:

  test("settingsPath: Project is .orca/settings.properties under workDir"):
    val workDir = TempDirs.dir()
    assertEquals(
      Tier.Project.settingsPath(using TestShellEnv(workDir)),
      workDir / ".orca" / "settings.properties"
    )

  test("settingsPath: Global is the config home's settings file"):
    given env: ShellEnv = TestShellEnv()
    assertEquals(Tier.Global.settingsPath, env.configHome.settings)
