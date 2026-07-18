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
