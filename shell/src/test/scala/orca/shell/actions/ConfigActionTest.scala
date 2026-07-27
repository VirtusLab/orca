package orca.shell.actions

import orca.agents.BackendTag
import orca.settings.{AgentSettings, AgentSpec}

class ConfigActionTest extends munit.FunSuite:

  private def withTempPath(body: os.Path => Unit): Unit =
    val dir = os.temp.dir(prefix = "orca-config-action-test")
    try body(dir / "settings.properties")
    finally os.remove.all(dir)

  // --- show ---

  test("show returns AgentSettings.empty when the file doesn't exist"):
    withTempPath: path =>
      assertEquals(ConfigAction.show(path), Right(AgentSettings.empty))

  test("show parses the file's configured roles"):
    withTempPath: path =>
      os.write(path, "codingAgent = claude:sonnet\nreviewAgent = gemini\n")
      assertEquals(
        ConfigAction.show(path),
        Right(
          AgentSettings(
            planning = None,
            coding = Some(AgentSpec(BackendTag.ClaudeCode, Some("sonnet"))),
            review = Some(AgentSpec(BackendTag.Gemini, None))
          )
        )
      )

  test("show reports a malformed file as a Left instead of throwing"):
    withTempPath: path =>
      os.write(path, "not a valid line\n")
      assertEquals(
        ConfigAction.show(path),
        Left(
          "the global settings file is malformed — line 1: `not a valid line` is not a `#` comment and has no `=` — expected `key = value`"
        )
      )

  // --- set ---

  test("set writes a fresh file with a line per configured role"):
    withTempPath: path =>
      ConfigAction.set(
        path,
        AgentSettings(
          planning = Some(AgentSpec(BackendTag.ClaudeCode, None)),
          coding = Some(AgentSpec(BackendTag.Codex, None)),
          review = Some(AgentSpec(BackendTag.Gemini, None))
        )
      )
      assertEquals(
        ConfigAction.show(path).toOption.get.coding.map(_.backend),
        Some(BackendTag.Codex)
      )

  test("set surgically updates a cleanly-parsing file, preserving comments"):
    withTempPath: path =>
      os.write(path, "# a hand-written note\n\ncodingAgent = codex\n")
      ConfigAction.set(
        path,
        AgentSettings(coding = Some(AgentSpec(BackendTag.Gemini, None)))
      )
      val written = os.read(path)
      assert(written.startsWith("# a hand-written note\n\n"), written)
      assertEquals(
        ConfigAction.show(path).toOption.get.coding,
        Some(AgentSpec(BackendTag.Gemini, None))
      )

  test(
    "set rewrites a malformed file from scratch rather than patching junk through"
  ):
    withTempPath: path =>
      os.write(path, "not a valid line\n")
      ConfigAction.set(
        path,
        AgentSettings(coding = Some(AgentSpec(BackendTag.Codex, None)))
      )
      val written = os.read(path)
      assert(!written.contains("not a valid line"), written)
      assertEquals(
        ConfigAction.show(path).toOption.get.coding,
        Some(AgentSpec(BackendTag.Codex, None))
      )
