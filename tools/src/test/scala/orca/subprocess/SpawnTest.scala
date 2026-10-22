package orca.subprocess

class SpawnTest extends munit.FunSuite:

  test("the shutdown hook is removed once the process exits"):
    val sub = os.proc("true").spawn(destroyOnExit = false)
    val exitHook = Spawn.destroyOnJvmExit(sub)
    exitHook.removed.get()
    assert(!Runtime.getRuntime.removeShutdownHook(exitHook.hook))
