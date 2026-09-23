package orca.shell.menu

import orca.shell.flows.DiscoveredFlow
import orca.shell.ui.UiOutcome

import MenuFixtures.flow

class FlowPickerTest extends munit.FunSuite:

  // --- pickFlow ---

  private val threeFlows =
    List(flow("alpha.sc"), flow("implement.sc"), flow("zeta.sc"))

  test(
    "pickFlow: view/edit pickers (no default given) stay alphabetical"
  ):
    val ui = new RecordingSelectUi[DiscoveredFlow](UiOutcome.Cancelled)
    val _ = FlowPicker.pickFlow(ui, "View which flow?", threeFlows)
    assertEquals(
      ui.recordedChoices.head.map(_.value.name),
      List("alpha.sc", "implement.sc", "zeta.sc")
    )
