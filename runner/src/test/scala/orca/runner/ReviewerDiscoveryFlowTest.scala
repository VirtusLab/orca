package orca.runner

import orca.ReportedFailure
import orca.{ConfigHome, FlowContext, OrcaDir, StackSettings}
import orca.testkit.{GitRepo, TempDirs, currentBranch}
import orca.tools.OsGitTool

import java.util.concurrent.atomic.AtomicReference

/** End-to-end coverage of reviewer discovery through `runFlow`: each file tier
  * reaches the body's `reviewerCatalog`, and a malformed file stops the run
  * before it touches the tree.
  */
class ReviewerDiscoveryFlowTest extends munit.FunSuite:

  test("a project reviewer reaches the body's catalog and is announced"):
    val workDir = GitRepo.seeded()
    writeReviewer(OrcaDir.reviewersPath(workDir), "checks orca's own rules")
    val steps = new AtomicReference[List[String]](Nil)
    var names: List[String] = Nil
    driveFlow(workDir, listeners = List(FlowHarness.recordSteps(steps))):
      names = summon[FlowContext].reviewerCatalog.all.map(_.name.value)
    assert(names.contains("orca"), names.toString)
    assert(
      steps.get().contains("discovered reviewers: orca (project)"),
      steps.get().mkString("\n")
    )

  test("a user-global reviewer reaches the body's catalog and is announced"):
    val workDir = GitRepo.seeded()
    val configHome = ConfigHome(TempDirs.dir() / "orca")
    writeReviewer(configHome.reviewers, "checks the user's own rules")
    val steps = new AtomicReference[List[String]](Nil)
    var names: List[String] = Nil
    driveFlow(
      workDir,
      configHome = configHome,
      listeners = List(FlowHarness.recordSteps(steps))
    ):
      names = summon[FlowContext].reviewerCatalog.all.map(_.name.value)
    assert(names.contains("orca"), names.toString)
    assert(
      steps.get().contains("discovered reviewers: orca (global)"),
      steps.get().mkString("\n")
    )

  test("a malformed reviewer file aborts before the branch is created"):
    val workDir = GitRepo.seeded()
    os.write(
      OrcaDir.reviewersPath(workDir) / "orca.md",
      "---\nname: orca\n---\n\n## Scope\n",
      createFolders = true
    )
    val startBranch = new OsGitTool(workDir).currentBranch()
    val e = intercept[ReportedFailure]:
      driveFlow(workDir)(fail("the body must not run"))
    assert(e.cause.getMessage.contains("description:"), e.cause.getMessage)
    // The abort precedes `ensureClean` and any branch creation.
    assertEquals(new OsGitTool(workDir).currentBranch(), startBranch)

  test("a symlinked .orca/reviewers directory aborts before the branch"):
    // `os.isDir` follows links, so the per-file check cannot see this; the
    // guard is `OrcaDir.assertNoOrcaSymlinks` at the discovery call site.
    val workDir = GitRepo.seeded()
    val outside = TempDirs.dir("orca-outside-")
    writeReviewer(outside, "from outside the tree")
    os.makeDir.all(OrcaDir.rootPath(workDir))
    os.symlink(OrcaDir.reviewersPath(workDir), outside)
    val startBranch = new OsGitTool(workDir).currentBranch()
    val e = intercept[ReportedFailure]:
      driveFlow(workDir)(fail("the body must not run"))
    assert(e.cause.getMessage.contains("symlink"), e.cause.getMessage)
    assertEquals(new OsGitTool(workDir).currentBranch(), startBranch)

  private def writeReviewer(dir: os.Path, description: String): Unit =
    os.write(
      dir / "orca.md",
      s"---\ndescription: $description\n---\n\n## Scope\n",
      createFolders = true
    )

  private def driveFlow(
      workDir: os.Path,
      configHome: ConfigHome = FlowHarness.absentConfigHome(),
      listeners: List[orca.events.OrcaListener] = Nil
  )(body: (orca.FlowContext, orca.FlowControl) ?=> Unit): Unit =
    FlowHarness.driveFlow(
      workDir = workDir,
      wiring = FlowWiring(claude = Some(_ => StubAgent.claude)),
      flowName = "reviewer-discovery",
      configHome = configHome,
      stackSettings = Some(StackSettings.empty),
      listeners = listeners
    )(body)
