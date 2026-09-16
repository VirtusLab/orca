package orca.pr

import munit.FunSuite
import orca.{FlowControl, OrcaFlowException, stage}

import ox.{fork, supervised}
import scala.jdk.CollectionConverters.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/** Pins [[recordOpenedPr]]'s placement: it compiles only inside a stage, runs
  * only on the flow thread, and what it writes there outlives a resume that
  * replays the stage.
  */
class RecordOpenedPrTest extends FunSuite:

  test("recordOpenedPr outside a stage does not compile"):
    val errors = compileErrors(
      """
      given FlowControl = ???
      recordOpenedPr(samplePr)
      """
    )
    assert(
      errors.contains("(using WorkspaceWrite)"),
      s"expected the WorkspaceWrite implicitNotFound message, got: $errors"
    )

  test("the recorded PR survives a replayed create stage"):
    val (dir, store) = seededPrRepo()
    def open(calls: ConcurrentLinkedQueue[String]): Unit =
      given FlowControl = prControl(dir, store, _ => (), calls)
      val _ = stage("open"):
        calls.add("body"): Unit
        recordOpenedPr(samplePr)
        "done"
    open(new ConcurrentLinkedQueue[String]())
    val resumed = new ConcurrentLinkedQueue[String]()
    open(resumed)
    assertEquals(resumed.asScala.toList, Nil, "the body re-ran")
    assertEquals(store.load().flatMap(_.openedPr), Some(samplePr))

  test(
    "recordOpenedPr called from a fork inside a stage throws the R12 message"
  ):
    // A stage body's `WorkspaceWrite` is capturable into a fork wherever
    // capture checking is off, so the read-modify-write behind the record
    // needs the runtime assert.
    val (dir, store) = seededPrRepo()
    given FlowControl =
      prControl(dir, store, _ => (), new ConcurrentLinkedQueue[String]())
    val caught = new AtomicReference[Throwable](null)
    val _ = stage("open"):
      supervised:
        fork:
          // Caught in the fork so the assertion does not race `supervised`'s
          // own cross-thread exception machinery.
          try recordOpenedPr(samplePr)
          catch case e: Throwable => caught.set(e)
        .join()
      "done"
    val thrown = caught.get()
    assert(thrown.isInstanceOf[OrcaFlowException], s"got $thrown")
    assert(
      thrown.getMessage.contains("recordOpenedPr(...) called from a fork"),
      thrown.getMessage
    )
