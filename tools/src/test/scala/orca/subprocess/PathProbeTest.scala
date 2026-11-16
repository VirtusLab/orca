package orca.subprocess

import orca.testkit.{Effects, TempDirs}

class PathProbeTest extends munit.FunSuite:
  private val procs = Effects.procs

  test("resolves: a word on PATH resolves"):
    assert(PathProbe.resolves(procs, "bash", os.pwd))

  test("resolves: an unknown word does not resolve"):
    assert(!PathProbe.resolves(procs, "definitely-not-a-binary-xyz", os.pwd))

  test(
    "resolves: shell metacharacters in the word are never interpreted — no side effects"
  ):
    // The word travels as an ARGUMENT to `command -v -- "$1"`, never spliced
    // into the script text: this must not create `marker`, whatever the
    // resolution verdict on the (unresolvable) word is.
    val dir = TempDirs.dir()
    val result = PathProbe.resolves(procs, "echo; touch marker", dir)
    assert(!result)
    assert(!os.exists(dir / "marker"))
