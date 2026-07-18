package orca.shell

class ShellVersionTest extends munit.FunSuite:

  test("value falls back to \"dev\" when run from class directories (no jar manifest)"):
    assertEquals(ShellVersion.value, "dev")

  test("isRelease is false for the running (class-dir) build"):
    assert(!ShellVersion.isRelease)

  test("isRelease(v) is true for a plain semver tag"):
    assert(ShellVersion.isRelease("0.0.18"))

  test("isRelease(v) is false for a dynver snapshot with a '+' suffix"):
    assert(!ShellVersion.isRelease("0.0.18+5-abc"))

  test("isRelease(v) is false for \"dev\""):
    assert(!ShellVersion.isRelease("dev"))
