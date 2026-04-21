package orca.claude

class StubCliRunnerTest extends munit.FunSuite:
  test("returns the canned response and records the call"):
    val canned = CliResult(exitCode = 0, stdout = "hi", stderr = "")
    val runner = new StubCliRunner(canned)

    val result = runner.run(
      args = Seq("claude", "-p", "hello"),
      stdin = "stdin-body",
      env = Map("FOO" -> "bar"),
      cwd = os.pwd
    )

    assertEquals(result, canned)
    assertEquals(runner.calls.size, 1)
    assertEquals(
      runner.lastCall.map(_.args),
      Some(List("claude", "-p", "hello"))
    )
    assertEquals(runner.lastCall.map(_.stdin), Some("stdin-body"))
