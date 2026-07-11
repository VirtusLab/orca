package orca

import orca.events.EventDispatcher

import java.util.concurrent.atomic.AtomicReference
import ox.{fork, supervised}

/** R12 thread-affinity assert (ADR 0018 §2.2): `stage(...)` /
  * `agent.session(...)` may only run on the thread that constructed the
  * `StageFrames`-backed `FlowControl`. Exercised through [[TestFlowControl]] —
  * the mixin makes every `StageFrames` implementation (production and test
  * doubles alike) inherit the same assert, so this test doubles as the
  * production regression guard.
  */
class StageFramesTest extends munit.FunSuite:

  test("stage(...) called from an ox fork throws the R12 message"):
    val (ctx, _) = TestFlowControl.create(new EventDispatcher(Nil))
    given FlowControl = ctx
    // The fork catches internally and stashes the throwable, rather than
    // letting it propagate through `supervised`'s own cross-thread exception
    // machinery — keeps the assertion below independent of ox's scope-ending
    // race (a fork that lets an exception escape uncaught can end the scope
    // before the exception is observably the *cause*).
    val caught = new AtomicReference[Throwable](null)
    supervised:
      fork:
        try
          val _ = stage("in-a-fork")("should never run")
        catch case e: Throwable => caught.set(e)
      .join()
    val thrown = caught.get()
    assert(
      thrown != null,
      "expected stage(...) to throw when called from a fork"
    )
    assert(
      thrown.isInstanceOf[OrcaFlowException],
      s"expected an OrcaFlowException, got $thrown"
    )
    assert(
      thrown.getMessage.contains(
        "stage(...)/session(...) called from a fork — forks get FlowContext only (ADR 0018 R12)"
      ),
      s"unexpected message: ${thrown.getMessage}"
    )

  test("stage(...) called on the owning thread still succeeds"):
    val (ctx, _) = TestFlowControl.create(new EventDispatcher(Nil))
    given FlowControl = ctx
    val result = stage("same-thread")("ok")
    assertEquals(result, "ok")
