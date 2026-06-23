package orca

import orca.events.{EventDispatcher, OrcaEvent, OrcaListener}

import java.util.concurrent.atomic.AtomicReference

class FlowTest extends munit.FunSuite:

  private class RecordingListener extends OrcaListener:
    private val seen: AtomicReference[List[OrcaEvent]] = AtomicReference(Nil)
    def onEvent(event: OrcaEvent): Unit =
      val _ = seen.updateAndGet(event :: _)
    def events: List[OrcaEvent] = seen.get().reverse

  private def fixture: (RecordingListener, FlowControl) =
    val listener = new RecordingListener
    val (control, _) =
      TestFlowControl.create(new EventDispatcher(List(listener)))
    (listener, control)

  test("stage emits StageStarted then StageCompleted around the body"):
    val (listener, ctx) = fixture
    given FlowControl = ctx
    val result = stage("plan")(7)
    assertEquals(result, 7)
    assertEquals(
      listener.events.collect {
        case e: OrcaEvent.StageStarted   => e
        case e: OrcaEvent.StageCompleted => e
      },
      List(
        OrcaEvent.StageStarted("plan"),
        OrcaEvent.StageCompleted("plan")
      )
    )

  test("stage emits Error and re-raises when the body throws"):
    val (listener, ctx) = fixture
    given FlowControl = ctx
    val _ = intercept[RuntimeException]:
      stage[String]("risky")(throw new RuntimeException("kaboom"))
    assert(
      listener.events.exists {
        case OrcaEvent.Error(msg) =>
          msg.contains("risky") && msg.contains("kaboom")
        case _ => false
      },
      s"expected an Error event mentioning the stage and cause, got: ${listener.events}"
    )
    assert(
      !listener.events.exists(_.isInstanceOf[OrcaEvent.StageCompleted]),
      "StageCompleted must not be emitted when the body fails"
    )

  test("stage does not double-emit Error when the body calls fail"):
    val (listener, ctx) = fixture
    given FlowControl = ctx
    val _ = intercept[OrcaFlowException]:
      stage[String]("plan")(orca.fail("already emitted")(using ctx))
    val errors = listener.events.collect { case e: OrcaEvent.Error => e }
    assertEquals(errors, List(OrcaEvent.Error("already emitted")))

  test("fail emits Error and throws OrcaFlowException with the given message"):
    val (listener, ctx) = fixture
    val thrown = intercept[OrcaFlowException](orca.fail("no good")(using ctx))
    assertEquals(thrown.getMessage, "no good")
    assertEquals(listener.events, List(OrcaEvent.Error("no good")))

  test("stage emits Error when body throws OrcaFlowException directly"):
    // Tool adapters throw `OrcaFlowException` outside `fail(...)`; the
    // stage catch must surface them or the user sees `exit 1` with no
    // diagnostic.
    val (listener, ctx) = fixture
    given FlowControl = ctx
    val _ = intercept[OrcaFlowException]:
      stage[String]("tool-call")(throw new OrcaFlowException("git push failed"))
    val errors = listener.events.collect { case e: OrcaEvent.Error => e }
    assertEquals(
      errors,
      List(OrcaEvent.Error("Stage 'tool-call' failed: git push failed"))
    )

  test("display emits a Step without a stage or commit"):
    val (listener, ctx) = fixture
    given FlowControl = ctx
    display("just a note")
    assertEquals(listener.events, List(OrcaEvent.Step("just a note")))
