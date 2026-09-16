package orca.pr

import munit.FunSuite
import orca.{FlowControl, stage}

import scala.jdk.CollectionConverters.*
import java.util.concurrent.ConcurrentLinkedQueue

/** Pins [[recordOpenedPr]]'s placement: it compiles only inside a stage, and
  * what it writes there outlives a resume that replays the stage.
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
      errors.contains("must be made inside a `stage(...)` body") &&
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
