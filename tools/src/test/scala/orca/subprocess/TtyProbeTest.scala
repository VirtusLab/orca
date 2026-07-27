package orca.subprocess

class TtyProbeTest extends munit.FunSuite:

  // sbt runs tests in a forked JVM (`Test / fork := true`) with `connectInput`
  // left at its default `false` and stdout captured back to sbt via a pipe —
  // neither stream is ever a real terminal here, so both probes must agree.
  test("stdin/stdout: neither is a tty in a forked test JVM"):
    assert(!TtyProbe.stdin())
    assert(!TtyProbe.stdout())
