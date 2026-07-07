package orca.tools

import orca.WorkspaceWrite

class OsFsToolTest extends munit.FunSuite:

  // Tests exercise gated mutators directly; mint the workspace-write token once
  // for the whole suite (tests are package `orca.tools`, so
  // `WorkspaceWrite.unsafe` is in reach).
  private given WorkspaceWrite = WorkspaceWrite.unsafe

  private def withFs(body: (OsFsTool, os.Path) => Unit): Unit =
    val tmp = os.temp.dir()
    body(new OsFsTool(base = tmp), tmp)

  test("write creates missing parent directories and read round-trips content"):
    withFs: (fs, tmp) =>
      fs.write("nested/dir/file.txt", "hello")
      assert(os.exists(tmp / "nested" / "dir" / "file.txt"))
      assertEquals(fs.read("nested/dir/file.txt"), Some("hello"))

  test("write overwrites an existing file"):
    withFs: (fs, _) =>
      fs.write("a.txt", "first")
      fs.write("a.txt", "second")
      assertEquals(fs.read("a.txt"), Some("second"))

  test("read returns None when the file is missing"):
    withFs: (fs, _) =>
      assertEquals(fs.read("does/not/exist.txt"), None)

  test("list returns files matching a shallow glob"):
    withFs: (fs, _) =>
      fs.write("a.scala", "")
      fs.write("b.scala", "")
      fs.write("c.txt", "")
      assertEquals(fs.list("*.scala").sorted, List("a.scala", "b.scala"))

  test("list supports recursive ** glob"):
    withFs: (fs, _) =>
      fs.write("src/main/scala/Foo.scala", "")
      fs.write("src/test/scala/Bar.scala", "")
      fs.write("other/Baz.scala", "")
      fs.write("readme.md", "")
      val scalaFiles = fs.list("src/**/*.scala").sorted
      assertEquals(
        scalaFiles,
        List("src/main/scala/Foo.scala", "src/test/scala/Bar.scala")
      )

  test("list returns empty when the glob root does not exist"):
    withFs: (fs, _) =>
      assertEquals(fs.list("does/not/exist/*.txt"), Nil)
