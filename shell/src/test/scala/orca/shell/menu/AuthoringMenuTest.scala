package orca.shell.menu

import orca.discovery.Origin
import orca.shell.{OrcaBuild, ShellEnv, TestShellEnv, Tier}
import orca.shell.create.{FlowAuthoring, FlowDestination}
import orca.shell.ui.UiOutcome
import orca.testkit.TempDirs

import MenuFixtures.{flow, flowAt, noEditor, withDumbTerminal}

/** The agent paths' launch itself is covered in `AuthorActionTest`; these cover
  * the menu-side prompting up to where a prompt is cancelled, and the hand
  * paths end to end through a fake `spawnEditor`.
  */
class AuthoringMenuTest extends munit.FunSuite:

  private given ShellEnv = TestShellEnv()

  // --- editFlow ---

  test("editFlow: cancelling the flow prompt asks nothing else"):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript = List(UiOutcome.Cancelled))
      AuthoringMenu.editFlow(ui, terminal, noEditor)
      assertEquals(ui.selectCount, 1)

  test("editFlow: cancelling the mode prompt asks nothing else"):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript =
        List(UiOutcome.Selected(flow("implement.sc")), UiOutcome.Cancelled)
      )
      AuthoringMenu.editFlow(ui, terminal, noEditor)
      assertEquals(ui.selectCount, 2)
      assertEquals(ui.inputMultilineCount, 0)

  test(
    "editFlow: Hand + a non-built-in flow opens the editor on its own path directly"
  ):
    withDumbTerminal: terminal =>
      val source =
        flowAt("my-flow.sc", Origin.Project, TempDirs.dir() / "my-flow.sc")
      val ui = FlowScriptedUi(selectScript =
        List(UiOutcome.Selected(source), UiOutcome.Selected(ChangeMode.Hand))
      )
      var spawned: Option[os.Path] = None
      AuthoringMenu.editFlow(
        ui,
        terminal,
        (_, path) => { spawned = Some(path); 0 }
      )
      assertEquals(spawned, Some(source.path))

  test(
    "editFlow: Hand + a built-in flow customizes into the picked tier, then opens the copy"
  ):
    withDumbTerminal: terminal =>
      val workDir = TempDirs.dir()
      val sourcePath = TempDirs.dir() / "my-flow.sc"
      os.write(sourcePath, "// x\n")
      val source = flowAt("my-flow.sc", Origin.BuiltIn, sourcePath)
      val ui = FlowScriptedUi(selectScript =
        List(
          UiOutcome.Selected(source),
          UiOutcome.Selected(ChangeMode.Hand),
          UiOutcome.Selected(Tier.Project)
        )
      )
      var spawned: Option[os.Path] = None
      AuthoringMenu.editFlow(
        ui,
        terminal,
        (_, path) => { spawned = Some(path); 0 }
      )(using TestShellEnv(workDir))
      val expected = workDir / ".orca" / "flows" / "my-flow.sc"
      assertEquals(spawned, Some(expected))
      assertEquals(os.read(expected), "// x\n")

  test(
    "editFlow: Hand + built-in, cancelling the customize-tier prompt opens no editor"
  ):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript =
        List(
          UiOutcome.Selected(flow("implement.sc")),
          UiOutcome.Selected(ChangeMode.Hand),
          UiOutcome.Cancelled
        )
      )
      AuthoringMenu.editFlow(
        ui,
        terminal,
        noEditor
      )

  test(
    "editFlow: Agent mode — cancelling the changes prompt asks nothing else"
  ):
    withDumbTerminal: terminal =>
      val source =
        flowAt("my-flow.sc", Origin.Project, os.root / "my-flow.sc")
      val ui = FlowScriptedUi(
        selectScript = List(
          UiOutcome.Selected(source),
          UiOutcome.Selected(ChangeMode.Agent)
        ),
        inputMultilineScript = List(UiOutcome.Cancelled)
      )
      AuthoringMenu.editFlow(ui, terminal, noEditor)
      assertEquals(ui.selectCount, 2)
      assertEquals(ui.inputMultilineCount, 1)

  test(
    "editFlow: Agent mode with a built-in source — cancelling the customize-tier prompt asks nothing else"
  ):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript =
        List(
          UiOutcome.Selected(flow("implement.sc")),
          UiOutcome.Selected(ChangeMode.Agent),
          UiOutcome.Cancelled
        )
      )
      AuthoringMenu.editFlow(ui, terminal, noEditor)
      assertEquals(ui.selectCount, 3)
      assertEquals(ui.inputMultilineCount, 0)

  test("editDestination: a Global flow is edited in place, with no repo"):
    val path = TempDirs.dir() / "g.sc"
    assertEquals(
      AuthoringMenu.editDestination(
        FlowScriptedUi(),
        flowAt("g.sc", Origin.Global, path)
      ),
      Some(FlowDestination.Global(path))
    )

  test("editDestination: a Project flow is committed into workDir"):
    val workDir = TempDirs.dir()
    val path = workDir / ".orca" / "flows" / "p.sc"
    assertEquals(
      AuthoringMenu.editDestination(
        FlowScriptedUi(),
        flowAt("p.sc", Origin.Project, path)
      )(using TestShellEnv(workDir)),
      Some(FlowDestination.Project(path, workDir))
    )

  // --- createNewFlow ---

  test("createNewFlow: cancelling the mode prompt asks nothing else"):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript = List(UiOutcome.Cancelled))
      AuthoringMenu.createNewFlow(ui, terminal, noEditor)
      assertEquals(ui.selectCount, 1)
      assertEquals(ui.inputMultilineCount, 0)
      assertEquals(ui.inputCount, 0)

  test("createNewFlow (Agent): cancelling the tier prompt asks nothing else"):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript =
        List(UiOutcome.Selected(ChangeMode.Agent), UiOutcome.Cancelled)
      )
      AuthoringMenu.createNewFlow(ui, terminal, noEditor)
      assertEquals(ui.selectCount, 2)
      assertEquals(ui.inputMultilineCount, 0)

  test(
    "createNewFlow (Agent): cancelling the goal prompt stops before anything is written"
  ):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(
        selectScript = List(
          UiOutcome.Selected(ChangeMode.Agent),
          UiOutcome.Selected(Tier.Project)
        ),
        inputMultilineScript = List(UiOutcome.Cancelled)
      )
      AuthoringMenu.createNewFlow(ui, terminal, noEditor)
      assertEquals(ui.selectCount, 2)
      assertEquals(ui.inputMultilineCount, 1)
      assertEquals(ui.inputCount, 0)

  test(
    "createNewFlow (Hand): cancelling the tier prompt asks for no filename"
  ):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript =
        List(UiOutcome.Selected(ChangeMode.Hand), UiOutcome.Cancelled)
      )
      AuthoringMenu.createNewFlow(ui, terminal, noEditor)
      assertEquals(ui.selectCount, 2)
      assertEquals(ui.inputCount, 0)

  test(
    "createNewFlow (Hand): cancelling the filename prompt writes nothing and opens no editor"
  ):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(
        selectScript = List(
          UiOutcome.Selected(ChangeMode.Hand),
          UiOutcome.Selected(Tier.Project)
        ),
        inputScript = List(UiOutcome.Cancelled)
      )
      AuthoringMenu.createNewFlow(ui, terminal, noEditor)

  test(
    "createNewFlow (Hand): writes the skeleton flow and opens it in the editor"
  ):
    withDumbTerminal: terminal =>
      val workDir = TempDirs.dir()
      val ui = FlowScriptedUi(
        selectScript = List(
          UiOutcome.Selected(ChangeMode.Hand),
          UiOutcome.Selected(Tier.Project)
        ),
        inputScript = List(UiOutcome.Selected("my-new-flow.sc"))
      )
      var spawned: Option[os.Path] = None
      AuthoringMenu.createNewFlow(
        ui,
        terminal,
        (_, path) => { spawned = Some(path); 0 }
      )(using TestShellEnv(workDir))
      val expected = workDir / ".orca" / "flows" / "my-new-flow.sc"
      assertEquals(spawned, Some(expected))
      assertEquals(
        os.read(expected),
        FlowAuthoring.skeletonFlow(OrcaBuild.current)
      )

  test(
    "createNewFlow (Hand): a filename collision re-prompts instead of aborting"
  ):
    withDumbTerminal: terminal =>
      val workDir = TempDirs.dir()
      os.write(
        workDir / ".orca" / "flows" / "taken.sc",
        "// existing\n",
        createFolders = true
      )
      val ui = FlowScriptedUi(
        selectScript = List(
          UiOutcome.Selected(ChangeMode.Hand),
          UiOutcome.Selected(Tier.Project)
        ),
        inputScript =
          List(UiOutcome.Selected("taken.sc"), UiOutcome.Selected("free.sc"))
      )
      var spawned: Option[os.Path] = None
      AuthoringMenu.createNewFlow(
        ui,
        terminal,
        (_, path) => { spawned = Some(path); 0 }
      )(using TestShellEnv(workDir))
      assertEquals(ui.inputCount, 2)
      assertEquals(spawned, Some(workDir / ".orca" / "flows" / "free.sc"))

  // --- createForkFlow ---

  test("createForkFlow: cancelling the source prompt asks nothing else"):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript = List(UiOutcome.Cancelled))
      AuthoringMenu.createForkFlow(ui, terminal, noEditor)
      assertEquals(ui.selectCount, 1)
      assertEquals(ui.inputMultilineCount, 0)
      assertEquals(ui.inputCount, 0)

  test(
    "createForkFlow: cancelling the tier prompt stops before the mode prompt"
  ):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript =
        List(UiOutcome.Selected(flow("implement.sc")), UiOutcome.Cancelled)
      )
      AuthoringMenu.createForkFlow(ui, terminal, noEditor)
      assertEquals(ui.selectCount, 2)
      assertEquals(ui.inputMultilineCount, 0)

  test(
    "createForkFlow: cancelling the mode prompt is the last stop before hand/agent would proceed"
  ):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(selectScript =
        List(
          UiOutcome.Selected(flow("implement.sc")),
          UiOutcome.Selected(Tier.Project),
          UiOutcome.Cancelled
        )
      )
      AuthoringMenu.createForkFlow(ui, terminal, noEditor)
      assertEquals(ui.selectCount, 3)
      assertEquals(ui.inputMultilineCount, 0)

  test(
    "createForkFlow (Agent): cancelling the changes prompt stops before authoring would launch"
  ):
    withDumbTerminal: terminal =>
      val ui = FlowScriptedUi(
        selectScript = List(
          UiOutcome.Selected(flow("implement.sc")),
          UiOutcome.Selected(Tier.Project),
          UiOutcome.Selected(ChangeMode.Agent)
        ),
        inputMultilineScript = List(UiOutcome.Cancelled)
      )
      AuthoringMenu.createForkFlow(ui, terminal, noEditor)
      assertEquals(ui.selectCount, 3)
      assertEquals(ui.inputMultilineCount, 1)

  test(
    "createForkFlow (Hand): copies the source to the auto target and opens it in the editor"
  ):
    withDumbTerminal: terminal =>
      val workDir = TempDirs.dir()
      val sourcePath = TempDirs.dir() / "implement.sc"
      os.write(sourcePath, "// source content\n")
      val source = flowAt("implement.sc", Origin.Global, sourcePath)
      val ui = FlowScriptedUi(selectScript =
        List(
          UiOutcome.Selected(source),
          UiOutcome.Selected(Tier.Project),
          UiOutcome.Selected(ChangeMode.Hand)
        )
      )
      var spawned: Option[os.Path] = None
      AuthoringMenu.createForkFlow(
        ui,
        terminal,
        (_, path) => { spawned = Some(path); 0 }
      )(using TestShellEnv(workDir))
      val expected = workDir / ".orca" / "flows" / "implement-fork.sc"
      assertEquals(spawned, Some(expected))
      assertEquals(os.read(expected), "// source content\n")

  test("modeChoices offers agent first — the default — then hand"):
    assertEquals(
      AuthoringMenu.modeChoices.map(_.value),
      List(ChangeMode.Agent, ChangeMode.Hand)
    )
    assertEquals(
      AuthoringMenu.modeChoices.map(_.label),
      List(
        "With an agent — describe the changes and let it work",
        "By hand — open in your editor"
      )
    )
