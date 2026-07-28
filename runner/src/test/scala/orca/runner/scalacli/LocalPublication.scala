package orca.runner.scalacli

/** The library published into the local Ivy cache, so a `scala-cli` script
  * carrying `//> using repository ivy2Local` links against the working tree's
  * build rather than a release from Maven Central.
  *
  * `version` is read back from sbt instead of pinned here: dynver derives it
  * from git state, so it moves with every commit and with a dirty working tree.
  */
private[scalacli] final case class LocalPublication(
    repoRoot: os.Path,
    version: String
)

private[scalacli] object LocalPublication:

  /** Publishes once per JVM — runner's tests fork one JVM and run sequentially,
    * so the scala-cli suites share a single sbt round-trip. Forced from test
    * bodies, never at suite construction, so a skipped suite doesn't pay for
    * it. Throws (failing every dependent test) when sbt fails.
    */
  lazy val published: LocalPublication =
    val repoRoot = findRepoRoot()
    val _ = sbt(repoRoot, "publishLocal")
    LocalPublication(repoRoot, readVersion(repoRoot))

  // `sbt --client` reuses a running sbt server when one exists, so the second
  // invocation (print version) skips the JVM cold-start.
  private def sbt(repoRoot: os.Path, args: String*): os.CommandResult =
    val result =
      os.proc("sbt", "--client", args).call(cwd = repoRoot, check = false)
    if result.exitCode != 0 then
      throw new RuntimeException(
        s"sbt ${args.mkString(" ")} failed: ${result.err.text()}"
      )
    result

  private def readVersion(repoRoot: os.Path): String =
    // `print version` aggregates across every subproject; pick the first
    // version line (starts with whitespace + a digit) — they're all the same
    // dynver value. `sbt --client` decorates output with ANSI escapes, so strip
    // them before matching.
    val ansiEscape = "\u001b\\[[0-9;]*[A-Za-z]".r
    sbt(repoRoot, "--error", "print version").out
      .lines()
      .iterator
      .map(line => ansiEscape.replaceAllIn(line, "").trim)
      .find(s => s.headOption.exists(_.isDigit))
      .getOrElse(
        throw new RuntimeException(
          "could not parse orca version from sbt output"
        )
      )

  /** Walk up from the test's working directory until we see a build.sbt. */
  private def findRepoRoot(): os.Path =
    @scala.annotation.tailrec
    def loop(p: os.Path): os.Path =
      if os.exists(p / "build.sbt") then p
      else if p == p / os.up then
        throw new RuntimeException(
          "No build.sbt found walking up from " + os.pwd
        )
      else loop(p / os.up)
    loop(os.pwd)
