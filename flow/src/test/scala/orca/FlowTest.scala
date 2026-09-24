package orca

import orca.events.{EventDispatcher, OrcaEvent, OrcaListener, StageOutcome}

import java.util.concurrent.atomic.AtomicReference

class FlowTest extends munit.FunSuite:

  private class RecordingListener extends OrcaListener:
    private val seen: AtomicReference[List[OrcaEvent]] = AtomicReference(Nil)
    def onEvent(event: OrcaEvent): Unit =
      val _ = seen.updateAndGet(event :: _)
    def events: List[OrcaEvent] = seen.get().reverse

  private def fixture: (RecordingListener, TestRun) =
    val listener = new RecordingListener
    (listener, TestRun.create(new EventDispatcher(List(listener))))

  private val planPath: StagePath.Stage = StagePath.FlowBody.child("plan", 0)

  test("stage emits StageStarted then StageEnded(Completed) around the body"):
    val (listener, run) = fixture
    import run.given
    val result = stage("plan")(7)
    assertEquals(result, 7)
    val markers = listener.events.collect:
      case e: OrcaEvent.StageStarted => e
      case e: OrcaEvent.StageEnded   => e
    assertEquals(
      markers,
      List(
        OrcaEvent.StageStarted(planPath),
        OrcaEvent.StageEnded(planPath, StageOutcome.Completed)
      )
    )

  test("a failing stage ends as Failed after its Error"):
    val (listener, run) = fixture
    import run.given
    val _ = intercept[RuntimeException]:
      stage[String]("plan")(throw new RuntimeException("kaboom"))
    assertEquals(
      listener.events,
      List(
        OrcaEvent.StageStarted(planPath),
        OrcaEvent.Error("Stage 'plan' failed: kaboom"),
        OrcaEvent.StageEnded(planPath, StageOutcome.Failed)
      )
    )

  test("a failure in a nested stage ends the inner stage, then the outer one"):
    val (listener, run) = fixture
    import run.given
    val _ = intercept[RuntimeException]:
      stage[String]("plan"):
        stage[String]("inner")(throw new RuntimeException("kaboom"))
    val ends = listener.events.collect:
      case e: OrcaEvent.StageEnded => e
    assertEquals(
      ends,
      List(
        OrcaEvent.StageEnded(
          planPath.child("inner", 0),
          StageOutcome.Failed
        ),
        OrcaEvent.StageEnded(planPath, StageOutcome.Failed)
      )
    )

  test("stage does not double-emit Error when the body calls fail"):
    val (listener, run) = fixture
    import run.given
    val _ = interceptReported[OrcaFlowException]:
      stage[String]("plan")(orca.fail("already emitted"))
    val errors = listener.events.collect { case e: OrcaEvent.Error => e }
    assertEquals(errors, List(OrcaEvent.Error("already emitted")))

  test("stage does not double-emit Error when a fork in the body calls fail"):
    val (listener, run) = fixture
    import run.given
    val _ = interceptReported[OrcaFlowException]:
      stage[String]("plan"):
        ox.par(orca.fail("from a fork"), "other")._2
    val errors = listener.events.collect { case e: OrcaEvent.Error => e }
    assertEquals(errors, List(OrcaEvent.Error("from a fork")))

  test(
    "fail emits Error and aborts with an OrcaFlowException carrying the message"
  ):
    val (listener, run) = fixture
    val thrown =
      interceptReported[OrcaFlowException](
        orca.fail("no good")(using run.context)
      )
    assertEquals(thrown.getMessage, "no good")
    assertEquals(listener.events, List(OrcaEvent.Error("no good")))

  test("stage emits Error when body throws OrcaFlowException directly"):
    // Tool adapters throw `OrcaFlowException` outside `fail(...)`; the
    // stage catch must surface them or the user sees `exit 1` with no
    // diagnostic.
    val (listener, run) = fixture
    import run.given
    val _ = interceptReported[OrcaFlowException]:
      stage[String]("tool-call")(throw new OrcaFlowException("git push failed"))
    val errors = listener.events.collect { case e: OrcaEvent.Error => e }
    assertEquals(
      errors,
      List(OrcaEvent.Error("Stage 'tool-call' failed: git push failed"))
    )

  test("display emits a Step without a stage or commit"):
    val (listener, run) = fixture
    import run.given
    display("just a note")
    assertEquals(listener.events, List(OrcaEvent.Step("just a note")))
