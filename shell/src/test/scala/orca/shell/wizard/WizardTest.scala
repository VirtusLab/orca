package orca.shell.wizard

import orca.agents.BackendTag
import orca.settings.{AgentSettings, AgentSpec, SettingsFile, SettingsScope}
import orca.shell.ui.{Choice, ShellUi, UiOutcome}

/** Records the `Choice` lists it was shown and replays a fixed queue of
  * outcomes for `select`; `Wizard` never calls `confirm`/`input`.
  */
private class ScriptedUi(script: List[UiOutcome[BackendTag]]) extends ShellUi:
  private var pending = script
  private var shown: List[List[Choice[BackendTag]]] = Nil

  def recordedChoices: List[List[Choice[BackendTag]]] = shown

  def select[A](title: String, choices: List[Choice[A]], preselect: Option[A] = None): UiOutcome[A] =
    shown = shown :+ choices.asInstanceOf[List[Choice[BackendTag]]]
    val outcome = pending.head
    pending = pending.tail
    outcome.asInstanceOf[UiOutcome[A]]

  def confirm(question: String, default: Boolean): UiOutcome[Boolean] =
    throw new UnsupportedOperationException("Wizard.run doesn't confirm")

  def input(prompt: String, default: Option[String] = None): UiOutcome[String] =
    throw new UnsupportedOperationException("Wizard.run doesn't prompt for input")

class WizardTest extends munit.FunSuite:

  private val probe: String => Boolean = Set("claude", "gemini").contains

  private def withTempPath(body: os.Path => Unit): Unit =
    val dir = os.temp.dir(prefix = "orca-wizard-test")
    try body(dir / "settings.properties")
    finally os.remove.all(dir)

  private def parse(content: String): AgentSettings =
    SettingsFile.parse(content, SettingsScope.UserGlobal).toOption.get.agents

  test("first run: writes explicit lines for all three roles, choices in BackendTag order"):
    withTempPath: path =>
      val ui = ScriptedUi(
        List(
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(BackendTag.Codex),
          UiOutcome.Selected(BackendTag.Gemini)
        )
      )
      val wizard = Wizard(ui, probe, path)
      assert(wizard.run(reconfigure = false))

      val agents = parse(os.read(path))
      assertEquals(agents.planning, Some(AgentSpec(BackendTag.ClaudeCode, None)))
      assertEquals(agents.coding, Some(AgentSpec(BackendTag.Codex, None)))
      assertEquals(agents.review, Some(AgentSpec(BackendTag.Gemini, None)))

      val tagsShown = ui.recordedChoices.map(_.map(_.value))
      val expectedOrder =
        List(BackendTag.ClaudeCode, BackendTag.Codex, BackendTag.Opencode, BackendTag.Pi, BackendTag.Gemini)
      assertEquals(tagsShown, List.fill(3)(expectedOrder))

  test("detection decorates labels but never disables a choice"):
    withTempPath: path =>
      val ui = ScriptedUi(List.fill(3)(UiOutcome.Selected(BackendTag.ClaudeCode)))
      assert(Wizard(ui, probe, path).run(reconfigure = false))

      val firstMenu = ui.recordedChoices.head
      assert(firstMenu.forall(_.isEnabled), "detection must never disable a choice")
      val byTag = firstMenu.map(c => c.value -> c.label).toMap
      assert(byTag(BackendTag.ClaudeCode).contains("✓ found"), byTag(BackendTag.ClaudeCode))
      assert(byTag(BackendTag.Codex).contains("not found on PATH"), byTag(BackendTag.Codex))
      assert(byTag(BackendTag.Gemini).contains("✓ found"), byTag(BackendTag.Gemini))

  test("re-configure preselects the current harness and keeps its model pin when unchanged"):
    withTempPath: path =>
      os.write(
        path,
        """codingAgent = claude:sonnet
          |planningAgent = claude
          |reviewAgent = gemini
          |""".stripMargin
      )
      val ui = ScriptedUi(
        List(
          UiOutcome.Selected(BackendTag.ClaudeCode), // planning: unchanged
          UiOutcome.Selected(BackendTag.ClaudeCode), // coding: re-chosen, keeps :sonnet
          UiOutcome.Selected(BackendTag.Gemini) // review: unchanged
        )
      )
      assert(Wizard(ui, probe, path).run(reconfigure = true))

      val agents = parse(os.read(path))
      assertEquals(agents.coding, Some(AgentSpec(BackendTag.ClaudeCode, Some("sonnet"))))

      // the coding-role menu (2nd select) preselects the current harness, marked "(current)"
      val codingMenu = ui.recordedChoices(1)
      val currentLabel = codingMenu.find(_.value == BackendTag.ClaudeCode).get.label
      assert(currentLabel.contains("(current)"), currentLabel)

  test("re-configure drops the model pin when the harness changes"):
    withTempPath: path =>
      os.write(path, "codingAgent = claude:sonnet\n")
      val ui = ScriptedUi(
        List(
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(BackendTag.Gemini), // coding: switched away from claude
          UiOutcome.Selected(BackendTag.ClaudeCode)
        )
      )
      assert(Wizard(ui, probe, path).run(reconfigure = true))

      val agents = parse(os.read(path))
      assertEquals(agents.coding, Some(AgentSpec(BackendTag.Gemini, None)))

  test("re-configure preserves unrelated comments and blank lines via the surgical update"):
    withTempPath: path =>
      os.write(path, "# a hand-written note\n\ncodingAgent = codex\n")
      val ui = ScriptedUi(
        List(
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(BackendTag.Codex),
          UiOutcome.Selected(BackendTag.Gemini)
        )
      )
      assert(Wizard(ui, probe, path).run(reconfigure = true))
      assert(os.read(path).startsWith("# a hand-written note\n\n"))

  test("Cancelled mid-wizard writes nothing"):
    withTempPath: path =>
      val ui = ScriptedUi(
        List(
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Cancelled
        )
      )
      assert(!Wizard(ui, probe, path).run(reconfigure = false))
      assert(!os.exists(path))

  test("Cancelled on the first prompt writes nothing"):
    withTempPath: path =>
      val ui = ScriptedUi(List(UiOutcome.Cancelled))
      assert(!Wizard(ui, probe, path).run(reconfigure = false))
      assert(!os.exists(path))
