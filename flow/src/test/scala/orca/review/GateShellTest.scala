package orca.review

import orca.testkit.{Effects, TempDirs}

class GateShellTest extends munit.FunSuite:

  test("run merges stderr into stdout"):
    val result = OsGateShell(Effects.procs, Effects.fs)
      .run("echo out; echo err >&2", TempDirs.dir())
    assertEquals((result.stdout, result.stderr), ("out\nerr\n", ""))
