package orca.util

import munit.FunSuite
import orca.testkit.TempDirs

class AtomicFileTest extends FunSuite:

  test("a failed replace leaves the target unchanged and no temp file"):
    val p = TempDirs.dir() / "doc.json"
    // A non-empty directory at the target: the rename over it fails.
    os.write(p / "inner", "kept", createFolders = true)
    val stagingDir = TempDirs.dir()
    val _ = intercept[java.nio.file.FileSystemException](
      AtomicFile.replace(p, stagingDir, Array[Byte](1))
    )
    assertEquals(os.read(p / "inner"), "kept")
    assertEquals(os.list(stagingDir).toList, Nil)
