package orca

import java.util.concurrent.atomic.AtomicReference

class FlowTest extends munit.FunSuite:

  private class RecordingListener extends OrcaListener:
    private val seen: AtomicReference[List[OrcaEvent]] = AtomicReference(Nil)
    def onEvent(event: OrcaEvent): Unit =
      val _ = seen.updateAndGet(event :: _)
    def events: List[OrcaEvent] = seen.get().reverse

  private def fixture: (RecordingListener, FlowContext) =
    val listener = new RecordingListener
    (listener, new TestFlowContext(new EventDispatcher(List(listener))))

  test("stage emits StageStarted then StageCompleted around the body"):
    val (listener, ctx) = fixture
    given FlowContext = ctx
    val result = stage("plan") { 7 }
    assertEquals(result, 7)
    assertEquals(
      listener.events,
      List(
        OrcaEvent.StageStarted("plan"),
        OrcaEvent.StageCompleted("plan", "7")
      )
    )

  test("stage emits Error and re-raises when the body throws"):
    val (listener, ctx) = fixture
    given FlowContext = ctx
    val _ = intercept[RuntimeException]:
      stage("risky") { throw new RuntimeException("kaboom") }
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
    given FlowContext = ctx
    val _ = intercept[OrcaFlowException]:
      stage("plan") { orca.fail("already emitted")(using ctx) }
    val errors = listener.events.collect { case e: OrcaEvent.Error => e }
    assertEquals(errors, List(OrcaEvent.Error("already emitted")))

  test("fail emits Error and throws OrcaFlowException with the given message"):
    val (listener, ctx) = fixture
    val thrown = intercept[OrcaFlowException](orca.fail("no good")(using ctx))
    assertEquals(thrown.getMessage, "no good")
    assertEquals(listener.events, List(OrcaEvent.Error("no good")))
