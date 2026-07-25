package orca.shell.wizard

import orca.agents.BackendTag
import orca.settings.{AgentSettings, AgentSpec, SettingsFile, SettingsScope}
import orca.shell.ui.{Choice, ShellUi, UiOutcome}

/** Records the `Choice` lists, `preselect` values, and `input` prompts it was
  * shown, and replays fixed queues of outcomes for `select`, `input`, and
  * `confirm`. `Wizard.run` calls `select` for a harness prompt, then either
  * `select` (curated model) or `input` (free-text model) per role;
  * `Wizard.repairMalformed` only calls `confirm`.
  */
private class ScriptedUi(
    selectScript: List[UiOutcome[Any]] = Nil,
    inputScript: List[UiOutcome[String]] = Nil,
    confirmScript: List[UiOutcome[Boolean]] = Nil
) extends ShellUi:
  private var pendingSelect = selectScript
  private var pendingInput = inputScript
  private var pendingConfirm = confirmScript
  private var shown: List[List[Choice[Any]]] = Nil
  private var preselects: List[Option[Any]] = Nil
  private var inputs: List[(String, Option[String])] = Nil

  def recordedChoices: List[List[Choice[Any]]] = shown
  def recordedPreselects: List[Option[Any]] = preselects
  def recordedInputs: List[(String, Option[String])] = inputs

  def select[A](
      title: String,
      choices: List[Choice[A]],
      preselect: Option[A] = None
  ): UiOutcome[A] =
    shown = shown :+ choices.asInstanceOf[List[Choice[Any]]]
    preselects = preselects :+ preselect.asInstanceOf[Option[Any]]
    val outcome = pendingSelect.head
    pendingSelect = pendingSelect.tail
    outcome.asInstanceOf[UiOutcome[A]]

  def confirm(question: String, default: Boolean): UiOutcome[Boolean] =
    val outcome = pendingConfirm.head
    pendingConfirm = pendingConfirm.tail
    outcome

  def input(prompt: String, default: Option[String] = None): UiOutcome[String] =
    inputs = inputs :+ (prompt -> default)
    val outcome = pendingInput.head
    pendingInput = pendingInput.tail
    outcome

  def inputMultiline(prompt: String): UiOutcome[String] =
    throw new UnsupportedOperationException(
      "Wizard doesn't prompt for multiline input"
    )

class WizardTest extends munit.FunSuite:

  private val probe: String => Boolean = Set("claude", "gemini").contains

  private def withTempPath(body: os.Path => Unit): Unit =
    val dir = os.temp.dir(prefix = "orca-wizard-test")
    try body(dir / "settings.properties")
    finally os.remove.all(dir)

  private def parse(content: String): AgentSettings =
    SettingsFile.parse(content, SettingsScope.UserGlobal).toOption.get.agents

  // A default no-pin path through every role, for tests that only care about
  // the harness choice: claude/claude/gemini, model steps waved through with
  // "harness default" (curated roles) and blank input (gemini).
  private def defaultRoleScript
      : (List[UiOutcome[Any]], List[UiOutcome[String]]) =
    (
      List(
        UiOutcome.Selected(BackendTag.ClaudeCode),
        UiOutcome.Selected(Wizard.ModelPick.Default),
        UiOutcome.Selected(BackendTag.Codex),
        UiOutcome.Selected(Wizard.ModelPick.Default),
        UiOutcome.Selected(BackendTag.Gemini)
      ),
      List(UiOutcome.Selected(""))
    )

  // --- first run: data ---

  test("first run writes explicit lines for all three roles"):
    withTempPath: path =>
      val (selectScript, inputScript) = defaultRoleScript
      val ui = ScriptedUi(selectScript, inputScript)
      assert(Wizard(ui, probe, path).run(reconfigure = false))

      val agents = parse(os.read(path))
      assertEquals(
        agents.planning,
        Some(AgentSpec(BackendTag.ClaudeCode, None))
      )
      assertEquals(agents.coding, Some(AgentSpec(BackendTag.Codex, None)))
      assertEquals(agents.review, Some(AgentSpec(BackendTag.Gemini, None)))

  test("a curated pick writes harness:model"):
    withTempPath: path =>
      val ui = ScriptedUi(
        selectScript = List(
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(Wizard.ModelPick.Curated("fable")),
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(Wizard.ModelPick.Curated("opus")),
          UiOutcome.Selected(BackendTag.Gemini)
        ),
        inputScript = List(UiOutcome.Selected(""))
      )
      assert(Wizard(ui, probe, path).run(reconfigure = false))

      val agents = parse(os.read(path))
      assertEquals(
        agents.planning,
        Some(AgentSpec(BackendTag.ClaudeCode, Some("fable")))
      )
      assertEquals(
        agents.coding,
        Some(AgentSpec(BackendTag.ClaudeCode, Some("opus")))
      )

  test("manual entry writes the typed model"):
    withTempPath: path =>
      val ui = ScriptedUi(
        selectScript = List(
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(Wizard.ModelPick.Manual),
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(Wizard.ModelPick.Default),
          UiOutcome.Selected(BackendTag.Gemini)
        ),
        inputScript = List(
          UiOutcome.Selected("claude-opus-4-8[1m]"),
          UiOutcome.Selected("")
        )
      )
      assert(Wizard(ui, probe, path).run(reconfigure = false))

      val agents = parse(os.read(path))
      assertEquals(
        agents.planning,
        Some(AgentSpec(BackendTag.ClaudeCode, Some("claude-opus-4-8[1m]")))
      )

  test("blank free text on an open-ended harness writes the bare harness"):
    withTempPath: path =>
      val ui = ScriptedUi(
        selectScript = List.fill(3)(UiOutcome.Selected(BackendTag.Opencode)),
        inputScript = List.fill(3)(UiOutcome.Selected(""))
      )
      assert(Wizard(ui, probe, path).run(reconfigure = false))

      val agents = parse(os.read(path))
      assertEquals(agents.planning, Some(AgentSpec(BackendTag.Opencode, None)))
      assertEquals(agents.coding, Some(AgentSpec(BackendTag.Opencode, None)))
      assertEquals(agents.review, Some(AgentSpec(BackendTag.Opencode, None)))

  test("free text with a typed model writes harness:model"):
    withTempPath: path =>
      val ui = ScriptedUi(
        selectScript = List.fill(3)(UiOutcome.Selected(BackendTag.Opencode)),
        inputScript = List(
          UiOutcome.Selected("anthropic/claude-sonnet-5"),
          UiOutcome.Selected(""),
          UiOutcome.Selected("")
        )
      )
      assert(Wizard(ui, probe, path).run(reconfigure = false))

      val agents = parse(os.read(path))
      assertEquals(
        agents.planning,
        Some(AgentSpec(BackendTag.Opencode, Some("anthropic/claude-sonnet-5")))
      )

  test(
    "preselect defaults per role on first run: planning fable/sol, coding/review opus/sol"
  ):
    withTempPath: path =>
      val (selectScript, inputScript) = defaultRoleScript
      val ui = ScriptedUi(selectScript, inputScript)
      assert(Wizard(ui, probe, path).run(reconfigure = false))

      assertEquals(
        ui.recordedPreselects(1),
        Some(Wizard.ModelPick.Curated("fable"))
      )
      assertEquals(
        ui.recordedPreselects(3),
        Some(Wizard.ModelPick.Curated("gpt-5.6-sol"))
      )

  test("preselect defaults for the review role: opus (claude)"):
    withTempPath: path =>
      val ui = ScriptedUi(
        selectScript = List(
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(Wizard.ModelPick.Default),
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(Wizard.ModelPick.Default),
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(Wizard.ModelPick.Default)
        )
      )
      assert(Wizard(ui, probe, path).run(reconfigure = false))
      assertEquals(
        ui.recordedPreselects(5),
        Some(Wizard.ModelPick.Curated("opus"))
      )

  // --- first run: UI shape ---

  test(
    "first run shows choices in BackendTag order for every role's harness prompt"
  ):
    withTempPath: path =>
      val (selectScript, inputScript) = defaultRoleScript
      val ui = ScriptedUi(selectScript, inputScript)
      assert(Wizard(ui, probe, path).run(reconfigure = false))

      // harness prompts are the 1st, 3rd and 5th select calls (claude/codex are
      // curated, so each is followed by a model select before the next role).
      val harnessMenus =
        List(0, 2, 4).map(ui.recordedChoices).map(_.map(_.value))
      val expectedOrder =
        List(
          BackendTag.ClaudeCode,
          BackendTag.Codex,
          BackendTag.Opencode,
          BackendTag.Pi,
          BackendTag.Gemini
        )
      assertEquals(harnessMenus, List.fill(3)(expectedOrder))

  test(
    "first run passes the fallback as preselect for every role's harness prompt"
  ):
    withTempPath: path =>
      // probe finds claude and gemini; BackendTag.values order puts ClaudeCode
      // first among the detected tags, so it's the fallback for every role.
      val ui = ScriptedUi(selectScript =
        List(
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(Wizard.ModelPick.Default),
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(Wizard.ModelPick.Default),
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(Wizard.ModelPick.Default)
        )
      )
      assert(Wizard(ui, probe, path).run(reconfigure = false))
      val harnessPreselects = List(0, 2, 4).map(ui.recordedPreselects)
      assertEquals(harnessPreselects, List.fill(3)(Some(BackendTag.ClaudeCode)))

  test("detection decorates harness labels but never disables a choice"):
    withTempPath: path =>
      val ui = ScriptedUi(selectScript =
        List(
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(Wizard.ModelPick.Default),
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(Wizard.ModelPick.Default),
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(Wizard.ModelPick.Default)
        )
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

  test(
    "re-configure preselects the pin's curated row and keeps it when re-chosen"
  ):
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
          UiOutcome.Selected(Wizard.ModelPick.Default),
          UiOutcome.Selected(
            BackendTag.ClaudeCode
          ), // coding: re-chosen, keeps :sonnet
          UiOutcome.Selected(Wizard.ModelPick.Curated("sonnet")),
          UiOutcome.Selected(BackendTag.Gemini) // review: unchanged
        ),
        inputScript = List(UiOutcome.Selected(""))
      )
      assert(Wizard(ui, probe, path).run(reconfigure = true))

      assertEquals(
        ui.recordedPreselects(3),
        Some(Wizard.ModelPick.Curated("sonnet"))
      )
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
          UiOutcome.Selected(Wizard.ModelPick.Default),
          UiOutcome.Selected(
            BackendTag.Gemini
          ), // coding: switched away from claude
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(Wizard.ModelPick.Default)
        ),
        inputScript = List(UiOutcome.Selected("")) // coding's gemini model
      )
      assert(Wizard(ui, probe, path).run(reconfigure = true))

      val agents = parse(os.read(path))
      assertEquals(agents.coding, Some(AgentSpec(BackendTag.Gemini, None)))

  test("re-configure onto harness default clears an existing pin"):
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
          UiOutcome.Selected(Wizard.ModelPick.Default),
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(Wizard.ModelPick.Default), // clears :sonnet
          UiOutcome.Selected(BackendTag.Gemini)
        ),
        inputScript = List(UiOutcome.Selected(""))
      )
      assert(Wizard(ui, probe, path).run(reconfigure = true))

      val agents = parse(os.read(path))
      assertEquals(agents.coding, Some(AgentSpec(BackendTag.ClaudeCode, None)))

  test(
    "re-configure prefills manual entry with a pin that isn't in the curated list"
  ):
    withTempPath: path =>
      os.write(path, "codingAgent = claude:claude-opus-4-8[1m]\n")
      val ui = ScriptedUi(
        selectScript = List(
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(Wizard.ModelPick.Default),
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(Wizard.ModelPick.Manual),
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(Wizard.ModelPick.Default)
        ),
        inputScript = List(UiOutcome.Selected("claude-opus-4-8[1m]"))
      )
      assert(Wizard(ui, probe, path).run(reconfigure = true))

      assertEquals(
        ui.recordedPreselects(3),
        Some(Wizard.ModelPick.Manual)
      )
      assertEquals(
        ui.recordedInputs.head,
        ("Coding model" -> Some("claude-opus-4-8[1m]"))
      )
      val agents = parse(os.read(path))
      assertEquals(
        agents.coding,
        Some(AgentSpec(BackendTag.ClaudeCode, Some("claude-opus-4-8[1m]")))
      )

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
          UiOutcome.Selected(Wizard.ModelPick.Default),
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(Wizard.ModelPick.Curated("sonnet")),
          UiOutcome.Selected(BackendTag.Gemini)
        ),
        inputScript = List(UiOutcome.Selected(""))
      )
      assert(Wizard(ui, probe, path).run(reconfigure = true))

      // the coding-role harness menu (3rd select overall) marks it "(current)"
      val codingMenu = ui.recordedChoices(2)
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
          UiOutcome.Selected(Wizard.ModelPick.Default),
          UiOutcome.Selected(BackendTag.Gemini),
          UiOutcome.Selected(BackendTag.Gemini)
        ),
        inputScript = List(UiOutcome.Selected(""), UiOutcome.Selected(""))
      )
      assert(Wizard(ui, probe, path).run(reconfigure = true))
      val harnessPreselects = List(0, 2, 3).map(ui.recordedPreselects)
      assertEquals(
        harnessPreselects,
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
          UiOutcome.Selected(Wizard.ModelPick.Default),
          UiOutcome.Selected(BackendTag.Codex),
          UiOutcome.Selected(Wizard.ModelPick.Default),
          UiOutcome.Selected(BackendTag.Gemini)
        ),
        inputScript = List(UiOutcome.Selected(""))
      )
      assert(Wizard(ui, probe, path).run(reconfigure = true))
      assert(os.read(path).startsWith("# a hand-written note\n\n"))

  test(
    "re-configure past a malformed file rewrites it from scratch instead of patching junk through"
  ):
    withTempPath: path =>
      os.write(path, "not a valid line\n")
      val (selectScript, inputScript) = defaultRoleScript
      val ui = ScriptedUi(selectScript, inputScript)
      assert(Wizard(ui, probe, path).run(reconfigure = true))

      // The gate: the malformed file is rewritten from scratch — the result
      // parses cleanly and the junk is gone. What the chosen roles serialise to
      // is covered by the first-run data test.
      val written = os.read(path)
      assert(
        SettingsFile.parse(written, SettingsScope.UserGlobal).isRight,
        s"the rewritten file must parse cleanly, got: $written"
      )
      assert(
        !written.contains("not a valid line"),
        s"the malformed junk must not survive, got: $written"
      )

  test(
    "first run over a pre-existing comments-only file preserves comments via the surgical update"
  ):
    withTempPath: path =>
      os.write(path, "# a hand-written note\n\n")
      val (selectScript, inputScript) = defaultRoleScript
      val ui = ScriptedUi(selectScript, inputScript)
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

  test("Cancelled on the Planning harness prompt writes nothing"):
    withTempPath: path =>
      val ui = ScriptedUi(selectScript = List(UiOutcome.Cancelled))
      assert(!Wizard(ui, probe, path).run(reconfigure = false))
      assert(!os.exists(path))

  test("Cancelled on a curated model prompt writes nothing"):
    withTempPath: path =>
      val ui = ScriptedUi(selectScript =
        List(UiOutcome.Selected(BackendTag.ClaudeCode), UiOutcome.Cancelled)
      )
      assert(!Wizard(ui, probe, path).run(reconfigure = false))
      assert(!os.exists(path))

  test("Cancelled on a later role's harness prompt writes nothing"):
    withTempPath: path =>
      val ui = ScriptedUi(selectScript =
        List(
          UiOutcome.Selected(BackendTag.ClaudeCode),
          UiOutcome.Selected(Wizard.ModelPick.Default),
          UiOutcome.Cancelled
        )
      )
      assert(!Wizard(ui, probe, path).run(reconfigure = false))
      assert(!os.exists(path))

  test("Cancelled on a free-text model prompt writes nothing"):
    withTempPath: path =>
      val ui = ScriptedUi(
        selectScript = List(UiOutcome.Selected(BackendTag.Gemini)),
        inputScript = List(UiOutcome.Cancelled)
      )
      assert(!Wizard(ui, probe, path).run(reconfigure = false))
      assert(!os.exists(path))

  // --- pure model-selection helpers ---

  test(
    "curatedModels lists CLI-resolved aliases for claude and codex, nothing for open-ended harnesses"
  ):
    assertEquals(
      Wizard.curatedModels(BackendTag.ClaudeCode).map(_._1),
      List("fable", "opus", "sonnet")
    )
    assertEquals(
      Wizard.curatedModels(BackendTag.Codex).map(_._1),
      List("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna")
    )
    assertEquals(Wizard.curatedModels(BackendTag.Opencode), Nil)
    assertEquals(Wizard.curatedModels(BackendTag.Pi), Nil)
    assertEquals(Wizard.curatedModels(BackendTag.Gemini), Nil)

  test(
    "defaultModelFor preselects the cheaper claude alias for planning, the flagship elsewhere"
  ):
    assertEquals(
      Wizard.defaultModelFor(Wizard.Role.Planning, BackendTag.ClaudeCode),
      Some("fable")
    )
    assertEquals(
      Wizard.defaultModelFor(Wizard.Role.Coding, BackendTag.ClaudeCode),
      Some("opus")
    )
    assertEquals(
      Wizard.defaultModelFor(Wizard.Role.Review, BackendTag.ClaudeCode),
      Some("opus")
    )
    assertEquals(
      Wizard.defaultModelFor(Wizard.Role.Planning, BackendTag.Codex),
      Some("gpt-5.6-sol")
    )
    assertEquals(
      Wizard.defaultModelFor(Wizard.Role.Coding, BackendTag.Codex),
      Some("gpt-5.6-sol")
    )
    assertEquals(
      Wizard.defaultModelFor(Wizard.Role.Planning, BackendTag.Gemini),
      None
    )

  test(
    "preselectModelPick prefers the matching curated row, then manual, then the default, then harness default"
  ):
    val curated = Wizard.curatedModels(BackendTag.ClaudeCode)
    assertEquals(
      Wizard.preselectModelPick(curated, Some("sonnet"), Some("fable")),
      Wizard.ModelPick.Curated("sonnet")
    )
    assertEquals(
      Wizard.preselectModelPick(
        curated,
        Some("claude-opus-4-8[1m]"),
        Some("fable")
      ),
      Wizard.ModelPick.Manual
    )
    assertEquals(
      Wizard.preselectModelPick(curated, None, Some("fable")),
      Wizard.ModelPick.Curated("fable")
    )
    assertEquals(
      Wizard.preselectModelPick(curated, None, None),
      Wizard.ModelPick.Default
    )

  test(
    "freeTextHint hints at the opencode and pi model formats, nothing elsewhere"
  ):
    assert(Wizard.freeTextHint(BackendTag.Opencode).contains("provider/model"))
    assert(Wizard.freeTextHint(BackendTag.Pi).contains(":thinking"))
    assertEquals(Wizard.freeTextHint(BackendTag.Gemini), "")

  test("blankToNone treats blank/whitespace-only input as no pin"):
    assertEquals(Wizard.blankToNone(""), None)
    assertEquals(Wizard.blankToNone("   "), None)
    assertEquals(Wizard.blankToNone(" sonnet "), Some("sonnet"))

  // --- repairMalformed ---

  test(
    "repairMalformed: accepting runs the wizard and rewrites the file with the chosen roles"
  ):
    withTempPath: path =>
      os.write(path, "not a valid line\n")
      val (selectScript, inputScript) = defaultRoleScript
      val ui = ScriptedUi(
        selectScript,
        inputScript,
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
