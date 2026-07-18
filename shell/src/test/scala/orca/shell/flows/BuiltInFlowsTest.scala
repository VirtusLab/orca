package orca.shell.flows

class BuiltInFlowsTest extends munit.FunSuite:

  private val resourcePrefix = "/orca/shell/flows/"

  private def resourceText(name: String): String =
    val stream = getClass.getResourceAsStream(resourcePrefix + name)
    assert(stream != null, s"missing resource $name")
    try new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally stream.close()

  private def indexNames: List[String] =
    resourceText("index").linesIterator.filter(_.nonEmpty).toList

  test("the generated index lists the six built-in flows"):
    assertEquals(
      indexNames.sorted,
      List(
        "epic.sc",
        "implement-enhanced.sc",
        "implement-interactive.sc",
        "implement.sc",
        "issue-pr-bugfix.sc",
        "issue-pr.sc"
      )
    )

  test("every indexed flow resource is readable and non-empty"):
    indexNames.foreach(name => assert(resourceText(name).trim.nonEmpty, name))

  private def withTempHome(body: os.Path => Unit): Unit =
    val home = os.temp.dir(prefix = "orca-built-in-flows-test")
    try body(home)
    finally os.remove.all(home)

  test("extracted falls back to home/.cache when XDG_CACHE_HOME is relative"):
    withTempHome: home =>
      val dir = BuiltInFlows.extracted(Map("XDG_CACHE_HOME" -> "rel/path").get, home, "0.0.18")
      assertEquals(dir, home / ".cache" / "orca" / "shell" / "0.0.18" / "flows")

  test("extracted (release version) creates the flows once, unchanged on a second call"):
    withTempHome: home =>
      val dir = BuiltInFlows.extracted(Map.empty.get, home, "0.0.18")
      assert(os.isDir(dir))
      val expectedNames = indexNames.sorted
      assertEquals(os.list(dir).map(_.last).toList.sorted, expectedNames)
      val mtimesBefore = expectedNames.map(n => n -> os.mtime(dir / n)).toMap

      val _ = BuiltInFlows.extracted(Map.empty.get, home, "0.0.18")

      val mtimesAfter = expectedNames.map(n => n -> os.mtime(dir / n)).toMap
      assertEquals(mtimesAfter, mtimesBefore)

  test("extracted (dev version) rewrites the dep pin and injects ivy2Local"):
    withTempHome: home =>
      val runningVersion = "0.0.18+5-abc123"
      val dir = BuiltInFlows.extracted(Map.empty.get, home, runningVersion)
      val content = os.read(dir / "epic.sc")
      val lines = content.linesIterator.toList
      val depLineIdx = lines.indexWhere(_.startsWith("//> using dep "))
      assert(depLineIdx >= 0, "expected a using-dep line")
      assertEquals(lines(depLineIdx), s"""//> using dep "org.virtuslab::orca:$runningVersion"""")
      assertEquals(lines(depLineIdx + 1), "//> using repository ivy2Local")

  test("extracted (release version) self-heals a half-populated leftover dir"):
    withTempHome: home =>
      // Simulates a process killed mid-extraction under the old
      // existence-keyed logic: the dir exists but only has 2 of 6 files.
      val dir = home / ".cache" / "orca" / "shell" / "0.0.18" / "flows"
      os.makeDir.all(dir)
      val expectedNames = indexNames.sorted
      expectedNames.take(2).foreach(name => os.write(dir / name, "stale-partial-content"))
      assertEquals(os.list(dir).map(_.last).toList.sorted, expectedNames.take(2))

      val result = BuiltInFlows.extracted(Map.empty.get, home, "0.0.18")

      assertEquals(result, dir)
      assertEquals(os.list(dir).map(_.last).toList.sorted, expectedNames)
      expectedNames.foreach(name => assert(os.read(dir / name).trim.nonEmpty, name))

  test("extracted (\"dev\") also rewrites the dep pin to the running version"):
    withTempHome: home =>
      val dir = BuiltInFlows.extracted(Map.empty.get, home, "dev")
      val lines = os.read(dir / "implement.sc").linesIterator.toList
      val depLineIdx = lines.indexWhere(_.startsWith("//> using dep "))
      assertEquals(lines(depLineIdx), """//> using dep "org.virtuslab::orca:dev"""")
      assertEquals(lines(depLineIdx + 1), "//> using repository ivy2Local")
