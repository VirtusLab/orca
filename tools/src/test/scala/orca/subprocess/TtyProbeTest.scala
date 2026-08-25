package orca.subprocess

class TtyProbeTest extends munit.FunSuite:

  // sbt runs tests in a forked JVM (`Test / fork := true`) with `connectInput`
  // left at its default `false` and stdout/stderr captured back to sbt via
  // pipes — no stream is ever a real terminal here, so every probe must agree.
  test("stdin/stdout/stderr: none is a tty in a forked test JVM"):
    assert(!TtyProbe.stdin())
    assert(!TtyProbe.stdout())
    assert(!TtyProbe.stderr())
