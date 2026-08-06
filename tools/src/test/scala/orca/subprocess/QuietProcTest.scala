package orca.subprocess

class QuietProcTest extends munit.FunSuite:

  /** A subprocess writing to stderr must have its output captured into
    * `result.err`, never inherited to the parent's terminal — the renderer's
    * StatusBar relies on this to avoid the staircase-spinner artifact.
    */
  test("call captures stderr into result.err"):
    val result = QuietProc.call(
      Seq("bash", "-c", "echo OUT; echo ERR 1>&2"),
      cwd = os.pwd
    )
    assertEquals(result.exitCode, 0)
    assertEquals(result.out.text().trim, "OUT")
    assertEquals(result.err.text().trim, "ERR")

  test("call captures stdout into result.out"):
    val result = QuietProc.call(Seq("echo", "hello world"), cwd = os.pwd)
    assertEquals(result.exitCode, 0)
    assertEquals(result.out.text().trim, "hello world")

  test("call doesn't throw on non-zero exit"):
    val result = QuietProc.call(Seq("bash", "-c", "exit 7"), cwd = os.pwd)
    assertEquals(result.exitCode, 7)

  test("callCapped keeps output of exactly the cap whole"):
    val result = QuietProc.callCapped(Seq("printf", "12345678"), maxBytes = 8)
    assertEquals(result.out, "12345678")
    assertEquals(result.truncated, false)

  test("callCapped cuts output one byte past the cap, and reports the cut"):
    val result = QuietProc.callCapped(Seq("printf", "123456789"), maxBytes = 8)
    assertEquals(result.out, "12345678")
    assertEquals(result.truncated, true)

  /** The capped path still owes its caller the failure text: `OsGitTool`'s read
    * turns stderr into the message an agent gets back.
    */
  test("callCapped captures stderr"):
    val result = QuietProc.callCapped(
      Seq("bash", "-c", "printf OUT; printf ERR 1>&2"),
      maxBytes = 64
    )
    assertEquals(result.out, "OUT")
    assertEquals(result.err, "ERR")

  test("callCapped caps stderr as well: it is no more bounded than stdout"):
    val result = QuietProc.callCapped(
      Seq("bash", "-c", "printf 123456789 1>&2"),
      maxBytes = 8
    )
    assertEquals(result.err, "12345678")
