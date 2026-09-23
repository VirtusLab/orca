package orca.util

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import munit.FunSuite
import orca.{OrcaDir, RunKey}
import orca.testkit.TempDirs

private case class Doc(v: Int)
private given JsonValueCodec[Doc] = JsonCodecMaker.make

/** How [[JsonFile.read]] classifies what it finds at a path. */
class JsonFileTest extends FunSuite:

  private def path(): os.Path = TempDirs.dir() / "dir" / "doc.json"

  test("a missing file is Absent"):
    assertEquals(JsonFile.read[Doc](path()), JsonFile.Read.Absent)

  test("write then read is Loaded"):
    val file = OrcaDir.progressFile(TempDirs.dir(), RunKey.of("p"))
    JsonFile.write(file, Doc(1))
    assertEquals(JsonFile.read[Doc](file.path), JsonFile.Read.Loaded(Doc(1)))

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
