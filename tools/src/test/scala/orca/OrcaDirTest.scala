package orca

import orca.testkit.TempDirs

class OrcaDirTest extends munit.FunSuite:

  test("ensureCache creates the cache dir with exact marker file contents"):
    val wd = TempDirs.dir()
    val cache = OrcaDir.ensureCache(wd)
    assertEquals(cache, wd / ".orca" / "cache")
    assert(os.isDir(cache))
    assertEquals(
      os.read(cache / ".gitignore"),
      "# Automatically created by orca.\n*\n"
    )
    assertEquals(
      os.read(cache / "CACHEDIR.TAG"),
      "Signature: 8a477f597d28d172789f06886806bc55\n" +
        "# This file marks .orca/cache as a cache directory, so backup tools skip it.\n"
    )

  test("second ensureCache call leaves existing marker files untouched"):
    val wd = TempDirs.dir()
    val cache = OrcaDir.ensureCache(wd)
    // Surviving canaries prove the files are written only when absent.
    os.write.over(cache / ".gitignore", "canary-gitignore")
    os.write.over(cache / "CACHEDIR.TAG", "canary-tag")
    assertEquals(OrcaDir.ensureCache(wd), cache)
    assertEquals(os.read(cache / ".gitignore"), "canary-gitignore")
    assertEquals(os.read(cache / "CACHEDIR.TAG"), "canary-tag")

  test("ensureRoot creates .orca only, without the cache dir"):
    val wd = TempDirs.dir()
    val root = OrcaDir.ensureRoot(wd)
    assertEquals(root, wd / ".orca")
    assert(os.isDir(root))
    assert(!os.exists(root / "cache"))
