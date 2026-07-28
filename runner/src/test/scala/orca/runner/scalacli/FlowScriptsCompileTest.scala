package orca.runner.scalacli

import orca.testkit.TempDirs

/** Compiles every flow script under `flows/` with `scala-cli`, linked against
  * the library as [[LocalPublication.published]] just built it. The flows are
  * real consumers of the public API, so a rename or a signature change breaks
  * them first. `flowtests.FlowCompilesTest` guards the same DSL surface from
  * inside sbt, but by hand-mirroring the usage; this suite builds the actual
  * files, which sbt's own compile never sees.
  *
  * Gated on `ORCA_INTEGRATION` because it shells out to a real sbt + scala-cli.
  * CI runs it as its own job (`.github/workflows/ci.yml`), setting the env var
  * for this suite alone: it is the one integration suite that needs no
  * credentials, only a `scala-cli` on PATH.
  */
class FlowScriptsCompileTest extends munit.FunSuite:

  override def munitTests(): Seq[Test] =
    if sys.env.contains("ORCA_INTEGRATION") then super.munitTests()
    else Nil

  override def munitTimeout: scala.concurrent.duration.Duration =
    import scala.concurrent.duration.DurationInt
    20.minutes

  /** Add new flow scripts here so they're picked up by the compile-check loop.
    * Each entry is a path under `flows/` (the seed scripts copy these `.sc`
    * files into the user's project at create-test-project time).
    */
  private val flowScripts: Seq[String] = Seq(
    "implement.sc",
    "implement-interactive.sc",
    "implement-enhanced.sc",
    "issue-pr-bugfix.sc",
    "issue-pr.sc",
    "epic.sc",
    "simple.sc"
  )

  for relPath <- flowScripts do
    test(s"flows/$relPath compiles via scala-cli"):
      val script = stagePinnedToLocal(LocalPublication.published, relPath)
      val result = os
        .proc("scala-cli", "compile", script.toString)
        .call(cwd = script / os.up, check = false, mergeErrIntoOut = true)
      assertEquals(
        result.exitCode,
        0,
        s"scala-cli compile failed for flows/$relPath:\n${result.out.text()}"
      )

  private val orcaDepPin =
    """^//> using dep "org\.virtuslab::orca:[^"]+"$""".r

  /** Copies `flows/$relPath` into a temp dir with its `//> using dep` line
    * repinned to the published version and `//> using repository ivy2Local`
    * inserted after it — the rewrite `orca.shell.flows.BuiltInFlows` and
    * `examples/runnable/_seed_lib.sh --local` also apply, neither reachable
    * from this module. Compiling the file as committed would resolve the last
    * *release* from Maven Central, so the check would pass no matter what the
    * working tree does to the API.
    *
    * Fails when no pin line matches: silently compiling against the released
    * artifact is the failure mode this suite exists to rule out.
    */
  private def stagePinnedToLocal(
      published: LocalPublication,
      relPath: String
  ): os.Path =
    val lines =
      os.read(published.repoRoot / "flows" / relPath).split("\n", -1).toList
    val pinned = lines.flatMap: line =>
      if orcaDepPin.matches(line) then
        List(
          s"""//> using dep "org.virtuslab::orca:${published.version}"""",
          "//> using repository ivy2Local"
        )
      else List(line)
    assertEquals(
      pinned.length,
      lines.length + 1,
      s"expected exactly one orca dep pin to rewrite in flows/$relPath"
    )
    val script = TempDirs.dir() / relPath
    os.write(script, pinned.mkString("\n"))
    script
