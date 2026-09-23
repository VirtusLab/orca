package orca.util

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import munit.FunSuite
import orca.testkit.TempDirs

private case class Doc(v: Int)
private given JsonValueCodec[Doc] = JsonCodecMaker.make

/** How [[JsonFile.read]] classifies what it finds at a path, and what
  * [[JsonFile.write]] leaves behind.
  */
class JsonFileTest extends FunSuite:

  private def path(): os.Path = TempDirs.dir() / "dir" / "doc.json"

  test("a missing file is Absent"):
    assertEquals(JsonFile.read[Doc](path()), JsonFile.Read.Absent)

  test("write then read is Loaded"):
    val p = path()
    os.makeDir.all(p / os.up)
    JsonFile.write(p, p / os.up, Doc(1))
    assertEquals(JsonFile.read[Doc](p), JsonFile.Read.Loaded(Doc(1)))

  test("a file that does not parse is Corrupt, with a reason"):
    val p = path()
    os.write(p, "not json {{{", createFolders = true)
    JsonFile.read[Doc](p) match
      case JsonFile.Read.Corrupt(reason) => assert(reason.nonEmpty)
      case other => fail(s"expected Corrupt, got $other")

  test("well-formed JSON the codec rejects is Corrupt"):
    val p = path()
    os.write(p, """{"w":1}""", createFolders = true)
    JsonFile.read[Doc](p) match
      case JsonFile.Read.Corrupt(_) => ()
      case other                    => fail(s"expected Corrupt, got $other")

  test("a directory at the path is Unreadable, not Corrupt"):
    val p = path()
    os.makeDir.all(p)
    JsonFile.read[Doc](p) match
      case JsonFile.Read.Unreadable(reason) => assert(reason.nonEmpty)
      case other => fail(s"expected Unreadable, got $other")

  test("a write leaves no temp file behind"):
    val p = path()
    os.makeDir.all(p / os.up)
    JsonFile.write(p, p / os.up, Doc(1))
    assertEquals(os.list(p / os.up).toList, List(p))

  test("a failed write leaves the target unchanged and no temp file"):
    val p = path()
    // A non-empty directory at the target: the rename over it fails.
    os.write(p / "inner", "kept", createFolders = true)
    val tempDir = TempDirs.dir()
    val _ = intercept[java.nio.file.FileSystemException](
      JsonFile.write(p, tempDir, Doc(1))
    )
    assertEquals(os.read(p / "inner"), "kept")
    assertEquals(os.list(tempDir).toList, Nil)
