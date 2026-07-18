package orca.shell.wizard

class FirstRunTest extends munit.FunSuite:

  private def withTempPath(present: Boolean, content: String = "")(body: os.Path => Unit): Unit =
    val dir = os.temp.dir(prefix = "orca-first-run-test")
    try
      val path = dir / "settings.properties"
      if present then os.write(path, content)
      body(path)
    finally os.remove.all(dir)

  test("absent file is first-run"):
    withTempPath(present = false): path =>
      assertEquals(FirstRun.check(path), Right(true))

  test("comments-only file is first-run"):
    withTempPath(present = true, "# just a header\n"): path =>
      assertEquals(FirstRun.check(path), Right(true))

  test("a file with one role set is not first-run"):
    withTempPath(present = true, "codingAgent = claude\n"): path =>
      assertEquals(FirstRun.check(path), Right(false))

  test("a file with all three roles set is not first-run"):
    val content =
      """planningAgent = claude
        |codingAgent = codex
        |reviewAgent = gemini
        |""".stripMargin
    withTempPath(present = true, content): path =>
      assertEquals(FirstRun.check(path), Right(false))

  test("a malformed file is Left, not first-run"):
    withTempPath(present = true, "not a valid line\n"): path =>
      FirstRun.check(path) match
        case Left(message) => assert(message.contains("line 1"), message)
        case Right(_)       => fail("expected Left for a malformed file")
