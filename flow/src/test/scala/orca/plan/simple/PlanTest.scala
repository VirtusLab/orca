package orca.plan.simple

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import orca.JsonData

class PlanTest extends munit.FunSuite:

  /** The library's structured-call surface bounds on `JsonData[O]`,
    * which derives the codec we need; this test pins the round-trip
    * so a `derives JsonData` regression is caught early.
    */
  test("Plan round-trips through JSON via the JsonData codec"):
    val plan = Plan(
      tasks = List(
        Task(
          branchName = "add-multiply",
          summary = "Add multiply",
          prompt = "Add a multiply(int a, int b) method to Calculator."
        ),
        Task(
          branchName = "add-divide",
          summary = "Add divide",
          prompt = "Add a divide(int, int) method with a zero-divisor guard."
        )
      )
    )
    given codec: com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[Plan] =
      summon[JsonData[Plan]].codec
    val json = writeToString(plan)
    val parsed = readFromString[Plan](json)
    assertEquals(parsed, plan)

  test("logTo emits a single Step with the branch and per-task summary list"):
    val seen = new java.util.concurrent.atomic.AtomicReference[List[orca.OrcaEvent]](Nil)
    val listener = new orca.OrcaListener:
      def onEvent(event: orca.OrcaEvent): Unit =
        val _ = seen.updateAndGet(event :: _)
    given orca.FlowContext = new orca.TestFlowContext(
      new orca.EventDispatcher(List(listener))
    )
    val plan = Plan(List(
      Task("feat-a", "Add feature A", "do A"),
      Task("feat-b", "Add feature B", "do B")
    ))
    plan.logTo
    val steps = seen.get().reverse.collect {
      case orca.OrcaEvent.Step(msg) => msg
    }
    assertEquals(steps.size, 1, s"expected exactly one Step; got: $steps")
    val msg = steps.head
    assert(
      msg.startsWith("Planned 2 tasks on branch 'feat-a'"),
      s"expected the header; got: $msg"
    )
    assert(msg.contains("- Add feature A"), s"missing task A; got: $msg")
    assert(msg.contains("- Add feature B"), s"missing task B; got: $msg")

  test("logTo on an empty plan emits nothing"):
    val seen = new java.util.concurrent.atomic.AtomicReference[List[orca.OrcaEvent]](Nil)
    val listener = new orca.OrcaListener:
      def onEvent(event: orca.OrcaEvent): Unit =
        val _ = seen.updateAndGet(event :: _)
    given orca.FlowContext = new orca.TestFlowContext(
      new orca.EventDispatcher(List(listener))
    )
    Plan(Nil).logTo
    assertEquals(seen.get(), Nil)
