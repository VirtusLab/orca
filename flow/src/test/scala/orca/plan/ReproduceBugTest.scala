package orca.plan

import munit.FunSuite
import orca.{
  InStage,
  OrcaFlowException,
  TestRun,
  WorkspaceWrite,
  interceptReported
}
import orca.agents.{Agent, BackendTag}
import orca.events.EventDispatcher
import orca.testkit.{PassthroughPrompts, ScriptedBackend, TestAgent}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

class ReproduceBugTest extends FunSuite:

  private given InStage = InStage.unsafe
  private given WorkspaceWrite = WorkspaceWrite.unsafe

  private val testPath = "src/test/DivTest.scala"

  /** Answers check turns with `verdicts` in order and records every writer
    * turn's prompt.
    */
  private class Scripted(verdicts: List[BugReportMatch]):
    private val pending = new ConcurrentLinkedQueue(verdicts.asJava)
    private val writerPrompts = new ConcurrentLinkedQueue[String]
    def writes: List[String] = writerPrompts.asScala.toList
    val agent: Agent[BackendTag.ClaudeCode.type] = TestAgent(
      ScriptedBackend.replying(BackendTag.ClaudeCode): turn =>
        if turn.prompt.contains(PlanPrompts.ReproductionCheck) then
          ScriptedBackend.json(pending.remove())
        else
          val _ = writerPrompts.add(turn.prompt)
          "written"
      ,
      "scripted",
      prompts = PassthroughPrompts
    )

  private def runWith(scripted: Scripted): Unit =
    val run = TestRun.create(new EventDispatcher(Nil))
    import run.given
    reproduceBug(
      request = "dividing by zero crashes",
      testPath = testPath,
      agent = scripted.agent
    )

  test("a first matching check needs no retry"):
    val scripted = Scripted(List(BugReportMatch(true, "same crash")))
    runWith(scripted)
    assertEquals(scripted.writes.size, 1)

  test("a first mismatch retries once with the check's explanation"):
    val scripted = Scripted(
      List(
        BugReportMatch(false, "fails to compile"),
        BugReportMatch(true, "ok")
      )
    )
    runWith(scripted)
    assertEquals(scripted.writes.size, 2)
    val retry = scripted.writes(1)
    assert(retry.contains("fails to compile"), retry)
    assert(retry.contains(testPath), retry)

  test("a second mismatch fails the stage with its explanation"):
    val scripted = Scripted(
      List(
        BugReportMatch(false, "first"),
        BugReportMatch(false, "still passes")
      )
    )
    val e = interceptReported[OrcaFlowException](runWith(scripted))
    assert(e.getMessage.contains("still passes"), e.getMessage)
    assertEquals(scripted.writes.size, 2)
