package orca.effect

class FileSystemTest extends munit.FunSuite:

  private val dir = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "fs-test"),
    teardown = os.remove.all(_)
  )

  private val fs = FileSystem.ambient

  dir.test("writeOver creates missing parent directories"): d =>
    fs.writeOver(d / "a" / "b" / "f.txt", "x")
    assertEquals(os.read(d / "a" / "b" / "f.txt"), "x")

  dir.test("files lists the accepted files, recursively"): d =>
    os.write(d / "top.txt", "", createFolders = true)
    os.write(d / "sub" / "nested.txt", "", createFolders = true)
    os.write(d / "sub" / "skipped.md", "", createFolders = true)
    os.makeDir(d / "empty")
    assertEquals(
      fs.files(d, _.ext == "txt").map(_.relativeTo(d).toString).toSet,
      Set("top.txt", "sub/nested.txt")
    )

  dir.test("findFile skips accepted directories"): d =>
    os.makeDir(d / "a.txt")
    os.write(d / "sub" / "b.txt", "", createFolders = true)
    assertEquals(fs.findFile(d, _.ext == "txt"), Some(d / "sub" / "b.txt"))

  dir.test("readPrefix stops at the end of a shorter file"): d =>
    os.write(d / "f.txt", "abc")
    assertEquals(
      fs.readPrefix(d / "f.txt", maxBytes = 512).toSeq,
      "abc".getBytes.toSeq
    )

  dir.test("removeAll removes a symlink, not its target"): d =>
    os.write(d / "target" / "f.txt", "", createFolders = true)
    os.symlink(d / "link", d / "target")
    fs.removeAll(d / "link")
    assert(!os.exists(d / "link", followLinks = false))
    assert(os.exists(d / "target" / "f.txt"))

  dir.test("realPath resolves a symlink"): d =>
    os.write(d / "target.txt", "")
    os.symlink(d / "link.txt", d / "target.txt")
    assertEquals(fs.realPath(d / "link.txt"), fs.realPath(d / "target.txt"))
