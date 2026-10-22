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

  test("createPr with a readiness keeps it over the run's"):
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
      .createPr("t", "b", Some(PrReadiness.Ready))
    assertEquals(seen.asScala.toList, List(Some(PrReadiness.Ready)))

  test("prHandle is the wrapped tool's"):
    val underlying = new StubGitHubTool:
      override def prHandle(ref: String): Either[String, PrHandle] = Right(pr)
    assertEquals(
      RunGitHubTool(underlying, PrReadiness.Draft).prHandle("x"),
      Right(pr)
    )
