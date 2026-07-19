package orca.shell.wizard

import orca.agents.BackendTag
import orca.settings.{AgentSettings, AgentSpec, SettingsFile, SettingsScope}
import orca.shell.ui.{Choice, ShellUi, UiOutcome}

/** Records the `Choice` lists and `preselect` values it was shown, and replays
  * fixed queues of outcomes for `select` and `confirm`. `Wizard.run` only calls
  * `select`; `Wizard.repairMalformed` only calls `confirm`.
  */
private class ScriptedUi(
    selectScript: List[UiOutcome[BackendTag]] = Nil,
    confirmScript: List[UiOutcome[Boolean]] = Nil
) extends ShellUi:
  private var pendingSelect = selectScript
  private var pendingConfirm = confirmScript
  private var shown: List[List[Choice[BackendTag]]] = Nil
  private var preselects: List[Option[BackendTag]] = Nil

  def recordedChoices: List[List[Choice[BackendTag]]] = shown
  def recordedPreselects: List[Option[BackendTag]] = preselects

  def select[A](
      title: String,
      choices: List[Choice[A]],
      preselect: Option[A] = None
  ): UiOutcome[A] =
    shown = shown :+ choices.asInstanceOf[List[Choice[BackendTag]]]
    preselects = preselects :+ preselect.asInstanceOf[Option[BackendTag]]
    val outcome = pendingSelect.head
    pendingSelect = pendingSelect.tail
    outcome.asInstanceOf[UiOutcome[A]]

  def confirm(question: String, default: Boolean): UiOutcome[Boolean] =
    val outcome = pendingConfirm.head
    pendingConfirm = pendingConfirm.tail
    outcome

  def input(prompt: String, default: Option[String] = None): UiOutcome[String] =
    throw new UnsupportedOperationException("Wizard doesn't prompt for input")

  def inputMultiline(prompt: String): UiOutcome[String] =
    throw new UnsupportedOperationException("Wizard doesn't prompt for input")

class WizardTest extends munit.FunSuite:

  private val probe: String => Boolean = Set("claude", "gemini").contains

  private def withTempPath(body: os.Path => Unit): Unit =
    val dir = os.temp.dir(prefix = "orca-wizard-test")
    try body(dir / "settings.properties")
    finally os.remove.all(dir)

  private def parse(content: String): AgentSettings =
    SettingsFile.parse(content, SettingsScope.UserGlobal).toOption.get.agents

  // --- first run: data ---

  test("first run writes explicit lines for all three roles"):
    withTempPath: path =>
      val ui = ScriptedUi(
        selectScript = List(
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(BackendTag.Codex),
          UiOutcome.Selected(BackendTag.Gemini)
        )
      )
      assert(Wizard(ui, probe, path).run(reconfigure = false))

      val agents = parse(os.read(path))
      assertEquals(
        agents.planning,
        Some(AgentSpec(BackendTag.ClaudeCode, None))
      )
      assertEquals(agents.coding, Some(AgentSpec(BackendTag.Codex, None)))
      assertEquals(agents.review, Some(AgentSpec(BackendTag.Gemini, None)))

  // --- first run: UI shape ---

  test("first run shows choices in BackendTag order for every role prompt"):
    withTempPath: path =>
      val ui = ScriptedUi(
        selectScript = List(
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(BackendTag.Codex),
          UiOutcome.Selected(BackendTag.Gemini)
        )
      )
      assert(Wizard(ui, probe, path).run(reconfigure = false))

      val tagsShown = ui.recordedChoices.map(_.map(_.value))
      val expectedOrder =
        List(
          BackendTag.ClaudeCode,
          BackendTag.Codex,
          BackendTag.Opencode,
          BackendTag.Pi,
          BackendTag.Gemini
        )
      assertEquals(tagsShown, List.fill(3)(expectedOrder))

  test("first run passes the fallback as preselect for every role"):
    withTempPath: path =>
      // probe finds claude and gemini; BackendTag.values order puts ClaudeCode
      // first among the detected tags, so it's the fallback for every role.
      val ui = ScriptedUi(selectScript =
        List.fill(3)(UiOutcome.Selected(BackendTag.ClaudeCode))
      )
      assert(Wizard(ui, probe, path).run(reconfigure = false))
      assertEquals(
        ui.recordedPreselects,
        List.fill(3)(Some(BackendTag.ClaudeCode))
      )

  test("detection decorates labels but never disables a choice"):
    withTempPath: path =>
      val ui = ScriptedUi(selectScript =
        List.fill(3)(UiOutcome.Selected(BackendTag.ClaudeCode))
      )
      assert(Wizard(ui, probe, path).run(reconfigure = false))

      val firstMenu = ui.recordedChoices.head
      assert(
        firstMenu.forall(_.isEnabled),
        "detection must never disable a choice"
      )
      val byTag = firstMenu.map(c => c.value -> c.label).toMap
      assert(
        byTag(BackendTag.ClaudeCode).contains("✓ found"),
        byTag(BackendTag.ClaudeCode)
      )
      assert(
        byTag(BackendTag.Codex).contains("not found on PATH"),
        byTag(BackendTag.Codex)
      )
      assert(
        byTag(BackendTag.Gemini).contains("✓ found"),
        byTag(BackendTag.Gemini)
      )

  // --- re-configure: data ---

  test("re-configure keeps the model pin when the harness is unchanged"):
    withTempPath: path =>
      os.write(
        path,
        """codingAgent = claude:sonnet
          |planningAgent = claude
          |reviewAgent = gemini
          |""".stripMargin
      )
      val ui = ScriptedUi(
        selectScript = List(
          UiOutcome.Selected(BackendTag.ClaudeCode), // planning: unchanged
          UiOutcome.Selected(
            BackendTag.ClaudeCode
          ), // coding: re-chosen, keeps :sonnet
          UiOutcome.Selected(BackendTag.Gemini) // review: unchanged
        )
      )
      assert(Wizard(ui, probe, path).run(reconfigure = true))

      val agents = parse(os.read(path))
      assertEquals(
        agents.coding,
        Some(AgentSpec(BackendTag.ClaudeCode, Some("sonnet")))
      )

  test("re-configure drops the model pin when the harness changes"):
    withTempPath: path =>
      os.write(path, "codingAgent = claude:sonnet\n")
      val ui = ScriptedUi(
        selectScript = List(
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(
            BackendTag.Gemini
          ), // coding: switched away from claude
          UiOutcome.Selected(BackendTag.ClaudeCode)
        )
      )
      assert(Wizard(ui, probe, path).run(reconfigure = true))

      val agents = parse(os.read(path))
      assertEquals(agents.coding, Some(AgentSpec(BackendTag.Gemini, None)))

  // --- re-configure: UI shape ---

  test("re-configure marks the current harness's choice as current"):
    withTempPath: path =>
      os.write(
        path,
        """codingAgent = claude:sonnet
          |planningAgent = claude
          |reviewAgent = gemini
          |""".stripMargin
      )
      val ui = ScriptedUi(
        selectScript = List(
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(BackendTag.Gemini)
        )
      )
      assert(Wizard(ui, probe, path).run(reconfigure = true))

      // the coding-role menu (2nd select) marks the current harness "(current)"
      val codingMenu = ui.recordedChoices(1)
      val currentLabel =
        codingMenu.find(_.value == BackendTag.ClaudeCode).get.label
      assert(currentLabel.contains("(current)"), currentLabel)

  test(
    "re-configure passes the current harness as preselect, not the fallback"
  ):
    withTempPath: path =>
      os.write(
        path,
        """codingAgent = gemini
          |planningAgent = claude
          |reviewAgent = gemini
          |""".stripMargin
      )
      val ui = ScriptedUi(
        selectScript = List(
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(BackendTag.Gemini),
          UiOutcome.Selected(BackendTag.Gemini)
        )
      )
      assert(Wizard(ui, probe, path).run(reconfigure = true))
      assertEquals(
        ui.recordedPreselects,
        List(
          Some(BackendTag.ClaudeCode),
          Some(BackendTag.Gemini),
          Some(BackendTag.Gemini)
        )
      )

  test(
    "re-configure preserves unrelated comments and blank lines via the surgical update"
  ):
    withTempPath: path =>
      os.write(path, "# a hand-written note\n\ncodingAgent = codex\n")
      val ui = ScriptedUi(
        selectScript = List(
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(BackendTag.Codex),
          UiOutcome.Selected(BackendTag.Gemini)
        )
      )
      assert(Wizard(ui, probe, path).run(reconfigure = true))
      assert(os.read(path).startsWith("# a hand-written note\n\n"))

  test(
    "re-configure past a malformed file rewrites it from scratch instead of patching junk through"
  ):
    withTempPath: path =>
      os.write(path, "not a valid line\n")
      val ui = ScriptedUi(
        selectScript = List(
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(BackendTag.Codex),
          UiOutcome.Selected(BackendTag.Gemini)
        )
      )
      assert(Wizard(ui, probe, path).run(reconfigure = true))

      val written = os.read(path)
      val parsed = SettingsFile.parse(written, SettingsScope.UserGlobal)
      assert(
        parsed.isRight,
        s"the rewritten file must parse cleanly, got: $written"
      )
      val agents = parsed.toOption.get.agents
      assertEquals(
        agents.planning,
        Some(AgentSpec(BackendTag.ClaudeCode, None))
      )
      assertEquals(agents.coding, Some(AgentSpec(BackendTag.Codex, None)))
      assertEquals(agents.review, Some(AgentSpec(BackendTag.Gemini, None)))
      assert(
        !written.contains("not a valid line"),
        s"the malformed junk must not survive, got: $written"
      )

  test(
    "first run over a pre-existing comments-only file preserves comments via the surgical update"
  ):
    withTempPath: path =>
      os.write(path, "# a hand-written note\n\n")
      val ui = ScriptedUi(
        selectScript = List(
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(BackendTag.Codex),
          UiOutcome.Selected(BackendTag.Gemini)
        )
      )
      assert(Wizard(ui, probe, path).run(reconfigure = false))

      val written = os.read(path)
      assert(written.startsWith("# a hand-written note\n\n"), written)
      val agents = parse(written)
      assertEquals(
        agents.planning,
        Some(AgentSpec(BackendTag.ClaudeCode, None))
      )
      assertEquals(agents.coding, Some(AgentSpec(BackendTag.Codex, None)))
      assertEquals(agents.review, Some(AgentSpec(BackendTag.Gemini, None)))

  // --- cancellation ---

  test("Cancelled on the first (Planning) prompt writes nothing"):
    withTempPath: path =>
      val ui = ScriptedUi(selectScript = List(UiOutcome.Cancelled))
      assert(!Wizard(ui, probe, path).run(reconfigure = false))
      assert(!os.exists(path))

  test("Cancelled on the second (Coding) prompt writes nothing"):
    withTempPath: path =>
      val ui = ScriptedUi(
        selectScript = List(
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Cancelled
        )
      )
      assert(!Wizard(ui, probe, path).run(reconfigure = false))
      assert(!os.exists(path))

  test("Cancelled on the third (Review) prompt writes nothing"):
    withTempPath: path =>
      val ui = ScriptedUi(
        selectScript = List(
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(BackendTag.Codex),
          UiOutcome.Cancelled
        )
      )
      assert(!Wizard(ui, probe, path).run(reconfigure = false))
      assert(!os.exists(path))

  // --- resolvedSpec: the pure pin-retention rule ---

  test("resolvedSpec keeps the model pin only when the harness is unchanged"):
    val current = Some(AgentSpec(BackendTag.ClaudeCode, Some("sonnet")))
    assertEquals(
      Wizard.resolvedSpec(
        BackendTag.ClaudeCode,
        Some(BackendTag.ClaudeCode),
        current
      ),
      AgentSpec(BackendTag.ClaudeCode, Some("sonnet"))
    )
    assertEquals(
      Wizard
        .resolvedSpec(BackendTag.Gemini, Some(BackendTag.ClaudeCode), current),
      AgentSpec(BackendTag.Gemini, None)
    )
    assertEquals(
      Wizard.resolvedSpec(BackendTag.ClaudeCode, None, None),
      AgentSpec(BackendTag.ClaudeCode, None)
    )

  // --- repairMalformed ---

  test(
    "repairMalformed: accepting runs the wizard and rewrites the file with the chosen roles"
  ):
    withTempPath: path =>
      os.write(path, "not a valid line\n")
      val ui = ScriptedUi(
        selectScript = List(
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(BackendTag.Codex),
          UiOutcome.Selected(BackendTag.Gemini)
        ),
        confirmScript = List(UiOutcome.Selected(true))
      )
      Wizard(ui, probe, path).repairMalformed()

      assert(
        ui.recordedChoices.nonEmpty,
        "accepting must run the wizard's role prompts"
      )
      val written = os.read(path)
      val agents = parse(written)
      assertEquals(
        agents.planning,
        Some(AgentSpec(BackendTag.ClaudeCode, None))
      )
      assertEquals(agents.coding, Some(AgentSpec(BackendTag.Codex, None)))
      assertEquals(agents.review, Some(AgentSpec(BackendTag.Gemini, None)))
      assert(
        !written.contains("not a valid line"),
        s"the malformed file must be replaced, got: $written"
      )

  test(
    "repairMalformed: declining leaves the file untouched and skips the wizard"
  ):
    withTempPath: path =>
      val original = "not a valid line\n"
      os.write(path, original)
      val ui = ScriptedUi(confirmScript = List(UiOutcome.Selected(false)))
      Wizard(ui, probe, path).repairMalformed()

      assertEquals(os.read(path), original)
      assertEquals(
        ui.recordedChoices,
        Nil,
        "declining must not run the wizard's role prompts"
      )

  test(
    "repairMalformed: cancelling mid-wizard leaves the original malformed content on disk"
  ):
    withTempPath: path =>
      val original = "not a valid line\n"
      os.write(path, original)
      val ui = ScriptedUi(
        selectScript = List(UiOutcome.Cancelled),
        confirmScript = List(UiOutcome.Selected(true))
      )
      Wizard(ui, probe, path).repairMalformed()

      assertEquals(
        os.read(path),
        original,
        "cancelling mid-wizard must not lose the original malformed content"
      )
