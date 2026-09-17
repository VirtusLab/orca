package orca.testkit

/** The repository root, for a test that reads a file the repo commits.
  *
  * sbt forks tests with the module directory as the working directory, so the
  * root is the nearest ancestor of it holding `AGENTS.md`. Not finding one is a
  * failure, not a reason to skip: every checkout has it.
  */
object RepoRoot:
  def dir: os.Path =
    Iterator
      .iterate(os.pwd)(_ / os.up)
      .takeWhile(_ != os.root)
      .find(d => os.exists(d / "AGENTS.md"))
      .getOrElse(throw new AssertionError(s"no AGENTS.md above ${os.pwd}"))
