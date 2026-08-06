package orca.testkit

class GitRepoTest extends munit.FunSuite:
  // The isolation itself is a build setting, not fixture code (build.sbt,
  // `Test / envVars`), so pin here that the test JVM really has it: without it
  // a developer's global config silently changes what fixtures do.
  test("a fixture repo sees no global or system git config"):
    val dir = GitRepo.empty()
    val nonLocal = os
      .proc("git", "config", "--list", "--show-scope")
      .call(cwd = dir)
      .out
      .lines()
      .filterNot(_.startsWith("local"))
    assertEquals(nonLocal.toList, Nil)
