package orca.shell.create

import orca.testkit.TempDirs

/** Compile-checks [[FlowAuthoring.skeletonFlow]]'s generated content via a real
  * `scala-cli` — the same kind of gap `FlowScriptsCompileTest` (runner module)
  * closes for the built-in flow scripts under `flows/`: sbt's own compile never
  * sees generated shell-authored text, so a Scala 3 syntax regression (e.g. a
  * comment-only `flow(...):` body) would otherwise only surface once a user
  * actually picked Create+hand. Gated on `ORCA_INTEGRATION` for the same reason
  * that suite is: shells out to a real sbt `publishLocal` + `scala-cli
  * compile`. It publishes on its own rather than reusing runner's
  * `LocalPublication` — that lives in another module's test sources.
  */
class FlowAuthoringSmokeTest extends munit.FunSuite:

  override def munitTests(): Seq[Test] =
    if sys.env.contains("ORCA_INTEGRATION") then super.munitTests() else Nil

  override def munitTimeout: scala.concurrent.duration.Duration =
    import scala.concurrent.duration.DurationInt
    10.minutes

  private case class Published(version: String)

  private val publishedRepo = new munit.Fixture[Published]("publishedRepo"):
    private var resolved: Published = null
    override def apply(): Published = resolved
    override def beforeAll(): Unit =
      val repoRoot = findRepoRoot()
      val publishResult = os
        .proc("sbt", "--client", "publishLocal")
        .call(cwd = repoRoot, check = false)
      assertEquals(
        publishResult.exitCode,
        0,
        s"publishLocal failed: ${publishResult.err.text()}"
      )
      val versionResult = os
        .proc("sbt", "--client", "--error", "print version")
        .call(cwd = repoRoot, check = false)
      assertEquals(
        versionResult.exitCode,
        0,
        s"reading version failed: ${versionResult.err.text()}"
      )
      val ansiEscape = "\u001b\\[[0-9;]*[A-Za-z]".r
      val version = versionResult.out
        .lines()
        .iterator
        .map(line => ansiEscape.replaceAllIn(line, "").trim)
        .find(s => s.headOption.exists(_.isDigit))
        .getOrElse(fail("could not parse orca version from sbt output"))
      resolved = Published(version)

  override def munitFixtures: Seq[munit.AnyFixture[?]] = Seq(publishedRepo)

  test("FlowAuthoring.skeletonFlow compiles via scala-cli"):
    val version = publishedRepo().version
    val dir = TempDirs.dir()
    val script = dir / "skeleton.sc"
    os.write(script, FlowAuthoring.skeletonFlow(version))
    val result = os
      .proc("scala-cli", "compile", script.toString)
      .call(cwd = dir, check = false, mergeErrIntoOut = true)
    assertEquals(
      result.exitCode,
      0,
      s"scala-cli compile failed for the generated skeleton:\n${result.out.text()}"
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
