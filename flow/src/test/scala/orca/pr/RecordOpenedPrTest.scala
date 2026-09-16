package orca.pr

import munit.FunSuite
import orca.{FlowControl, OrcaFlowException, stage}

import java.util.concurrent.ConcurrentLinkedQueue

/** Pins the two layers of [[recordOpenedPr]]'s outside-a-stage guard: the
  * direct in-stage call is rejected at compile time, the one routed through a
  * FlowControl-only helper at runtime.
  */
class RecordOpenedPrTest extends FunSuite:

  test("recordOpenedPr directly inside a stage body does not compile"):
    val errors = compileErrors(
      """
      given FlowControl = ???
      given orca.InStage = orca.InStage.unsafe
      recordOpenedPr(samplePr)
      """
    )
    assert(
      errors.contains("must be called outside a stage") &&
        errors.contains("recordOpenedPr(...)"),
      s"expected the OutsideStage implicitNotFound message, got: $errors"
    )

  test("recordOpenedPr inside a stage via a FlowControl-only helper throws"):
    val (dir, store) = seededPrRepo()
    given control: FlowControl = prControl(
      dir,
      store,
      _ => (),
      new ConcurrentLinkedQueue[String]()
    )
    def recordInHelper()(using FlowControl): Unit = recordOpenedPr(samplePr)
    val e = intercept[OrcaFlowException]:
      stage("open"):
        recordInHelper()
        "done"
    assert(e.getMessage.contains("after that stage returns"), e.getMessage)
    assertEquals(control.openedPr, None)
