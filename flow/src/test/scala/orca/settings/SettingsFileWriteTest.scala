package orca.settings

import munit.ScalaCheckSuite
import orca.agents.BackendTag
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** [[SettingsFile.renderGlobal]] and [[SettingsFile.updateGlobal]]: the
  * global-file write path the shell wizard drives (ADR 0020/0021). The example
  * suite pins exact shapes; the property pins the round-trip law.
  */
class SettingsFileWriteTest extends ScalaCheckSuite:

  private val specGen: Gen[AgentSpec] =
    for
      backend <- Gen.oneOf(BackendTag.values.toList)
      model <- Gen.option(Gen.alphaNumStr.suchThat(_.nonEmpty))
    yield AgentSpec(backend, model)

  private val agentsGen: Gen[AgentSettings] =
    for
      planning <- Gen.option(specGen)
      coding <- Gen.option(specGen)
      review <- Gen.option(specGen)
    yield AgentSettings(planning, coding, review)

  property("renderGlobal round-trips through parse(_, UserGlobal)"):
    forAll(agentsGen): agents =>
      assertEquals(
        SettingsFile
          .parse(SettingsFile.renderGlobal(agents), SettingsScope.UserGlobal)
          .map(_.agents),
        Right(agents)
      )

  test("renderGlobal of AgentSettings.empty carries only the header comment"):
    val rendered = SettingsFile.renderGlobal(AgentSettings.empty)
    assert(
      rendered.linesIterator.forall(_.trim.startsWith("#")),
      s"an all-unset AgentSettings should render no live key lines, got: $rendered"
    )
    assertEquals(
      SettingsFile.parse(rendered, SettingsScope.UserGlobal),
      Right(ParsedSettings(orca.StackSettings.empty, AgentSettings.empty))
    )

  test("renderGlobal renders one key = harness:model line per set role"):
    val agents = AgentSettings(
      planning = Some(AgentSpec(BackendTag.ClaudeCode, Some("opus"))),
      coding = Some(AgentSpec(BackendTag.Codex, None))
    )
    val rendered = SettingsFile.renderGlobal(agents)
    assert(
      rendered.contains("planningAgent = claude:opus"),
      s"should render the planning line, got: $rendered"
    )
    assert(
      rendered.contains("codingAgent = codex"),
      s"should render the coding line with no trailing colon, got: $rendered"
    )
    assert(
      !rendered.contains("reviewAgent"),
      s"an unset role must render no line, got: $rendered"
    )

  test("updateGlobal preserves a leading comment and blank lines"):
    val content =
      """# a hand-written header
        |
        |codingAgent = codex
        |""".stripMargin
    val updated = SettingsFile.updateGlobal(
      content,
      AgentSettings(coding = Some(AgentSpec(BackendTag.Codex, None)))
    )
    assert(
      updated.startsWith("# a hand-written header\n\n"),
      s"the leading comment and blank line must survive untouched, got: $updated"
    )

  test("updateGlobal replaces an existing agent-key line in place"):
    val content =
      """# a hand-written header
        |codingAgent = codex
        |reviewAgent = claude
        |""".stripMargin
    val updated = SettingsFile.updateGlobal(
      content,
      AgentSettings(coding =
        Some(AgentSpec(BackendTag.ClaudeCode, Some("opus")))
      )
    )
    assertEquals(
      updated,
      """# a hand-written header
        |codingAgent = claude:opus
        |reviewAgent = claude
        |""".stripMargin
    )

  test("updateGlobal appends a newly-set role at the end"):
    val content = "codingAgent = codex\n"
    val updated = SettingsFile.updateGlobal(
      content,
      AgentSettings(
        coding = Some(AgentSpec(BackendTag.Codex, None)),
        planning = Some(AgentSpec(BackendTag.ClaudeCode, None))
      )
    )
    assertEquals(updated, "codingAgent = codex\nplanningAgent = claude\n")

  test("updateGlobal leaves an unset role's existing line untouched"):
    // agents carries no `review` entry — a line naming reviewAgent survives
    // as-is, whatever its (possibly stale) value.
    val content =
      """codingAgent = codex
        |reviewAgent = claude:opus
        |""".stripMargin
    val updated = SettingsFile.updateGlobal(
      content,
      AgentSettings(coding = Some(AgentSpec(BackendTag.Codex, None)))
    )
    assertEquals(updated, content)

  property("updateGlobal's result re-parses cleanly under UserGlobal scope"):
    forAll(agentsGen, agentsGen): (before, after) =>
      val content = SettingsFile.renderGlobal(before)
      val updated = SettingsFile.updateGlobal(content, after)
      assert(
        SettingsFile.parse(updated, SettingsScope.UserGlobal).isRight,
        s"updateGlobal's output must re-parse, got:\n$updated"
      )
