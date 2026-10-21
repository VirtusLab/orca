package orca.runner

import orca.WorkspaceWrite
import orca.testkit.StubGitHubTool
import orca.tools.{PrHandle, PrReadiness}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

class RunGitHubToolTest extends munit.FunSuite:
  given WorkspaceWrite = WorkspaceWrite.unsafe

  private val pr =
    PrHandle.fromUrl("https://github.com/acme/widgets/pull/42").get

  /** The readiness the wrapped tool got for one `createPr(readiness)` through a
    * run whose `--draft` is `Draft`.
    */
  private def passedOn(
      readiness: Option[PrReadiness]
  ): List[Option[PrReadiness]] =
    val seen = new ConcurrentLinkedQueue[Option[PrReadiness]]()
    val underlying = new StubGitHubTool:
      override def createPr(
          title: String,
          body: String,
          readiness: Option[PrReadiness]
      )(using WorkspaceWrite) =
        seen.add(readiness): Unit
        Right(pr)
    val _ = RunGitHubTool(underlying, PrReadiness.Draft)
      .createPr("t", "b", readiness)
    seen.asScala.toList

  test("createPr without a readiness gets the run's"):
    assertEquals(passedOn(None), List(Some(PrReadiness.Draft)))

  test("createPr with a readiness keeps it"):
    assertEquals(
      passedOn(Some(PrReadiness.Ready)),
      List(Some(PrReadiness.Ready))
    )
