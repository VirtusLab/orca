package orca.settings

import munit.FunSuite
import orca.StackSettings
import orca.agents.BackendTag

class SettingsFileTest extends FunSuite:

  test("parse skips blank lines and lines whose first non-space char is #"):
    val content =
      """
        |# a comment
        |   # an indented comment
        |
        |format = cargo fmt
        |""".stripMargin
    assertEquals(
      SettingsFile.parse(content, SettingsScope.Project).map(_.stack),
      Right(StackSettings(format = List("cargo fmt")))
    )

  test("parse rejects a non-comment line without =, naming line and shape"):
    SettingsFile.parse(
      "format = cargo fmt\ncargo test\n",
      SettingsScope.Project
    ) match
      case Left(problem) =>
        assert(
          problem.message.contains("line 2"),
          s"should name the line: ${problem.message}"
        )
        assert(
          problem.message.contains("key = value"),
          s"should name the expected shape: ${problem.message}"
        )
      case Right(settings) => fail(s"expected a parse error, got: $settings")

  test("parse rejects an unknown key, naming it and the valid keys"):
    SettingsFile.parse("fromat = cargo fmt\n", SettingsScope.Project) match
      case Left(problem) =>
        assert(
          problem.message.contains("fromat"),
          s"should name the key: ${problem.message}"
        )
        SettingKey.values.foreach: valid =>
          assert(
            problem.message.contains(valid.raw),
            s"should list valid key `${valid.raw}`: ${problem.message}"
          )
      case Right(settings) => fail(s"expected a parse error, got: $settings")

  test("parse rejects a value whose first non-space char is #"):
    SettingsFile.parse("lint = # disabled\n", SettingsScope.Project) match
      case Left(problem) =>
        assert(
          problem.message.contains("line 1"),
          s"should name the line: ${problem.message}"
        )
        assert(
          problem.message.contains("comment out the whole line"),
          s"should tell the user to comment out the line: ${problem.message}"
        )
      case Right(settings) => fail(s"expected a parse error, got: $settings")

  test("parse takes the value verbatim after the first =, keeping embedded ="):
    assertEquals(
      SettingsFile
        .parse("lint = FOO=bar cargo check\n", SettingsScope.Project)
        .map(_.stack),
      Right(StackSettings(lint = List("FOO=bar cargo check")))
    )

  test("parse silently drops a key whose value is empty after trimming"):
    assertEquals(
      SettingsFile
        .parse("format =   \ntest = cargo test\n", SettingsScope.Project)
        .map(_.stack),
      Right(StackSettings(test = List("cargo test")))
    )

  test("parse appends repeated keys in file order"):
    assertEquals(
      SettingsFile
        .parse(
          "format = cargo fmt\nformat = pnpm exec prettier --write .\n",
          SettingsScope.Project
        )
        .map(_.stack),
      Right(
        StackSettings(format =
          List("cargo fmt", "pnpm exec prettier --write .")
        )
      )
    )

  test("parse treats keys as case-sensitive, rejecting a capitalised key"):
    SettingsFile.parse("Format = cargo fmt\n", SettingsScope.Project) match
      case Left(problem) =>
        assert(
          problem.message.contains("Format"),
          s"should name the key: ${problem.message}"
        )
      case Right(settings) => fail(s"expected a parse error, got: $settings")

  test("parse keeps a bare # inside the value (mid-line # is command text)"):
    assertEquals(
      SettingsFile
        .parse("format = echo '#1'\n", SettingsScope.Project)
        .map(_.stack),
      Right(StackSettings(format = List("echo '#1'")))
    )

  test("parse recognizes codingAgent against a bare harness name"):
    assertEquals(
      SettingsFile.parse("codingAgent = codex\n", SettingsScope.Project),
      Right(
        ParsedSettings(
          StackSettings.empty,
          AgentSettings(coding = Some(AgentSpec(BackendTag.Codex, None)))
        )
      )
    )

  test("parse recognizes planningAgent with a model pin"):
    assertEquals(
      SettingsFile
        .parse("planningAgent = claude:opus\n", SettingsScope.Project),
      Right(
        ParsedSettings(
          StackSettings.empty,
          AgentSettings(planning =
            Some(AgentSpec(BackendTag.ClaudeCode, Some("opus")))
          )
        )
      )
    )

  test("parse keeps everything after the first colon of reviewAgent verbatim"):
    assertEquals(
      SettingsFile.parse(
        "reviewAgent = opencode:anthropic/claude-sonnet-4-5\n",
        SettingsScope.Project
      ),
      Right(
        ParsedSettings(
          StackSettings.empty,
          AgentSettings(review =
            Some(
              AgentSpec(
                BackendTag.Opencode,
                Some("anthropic/claude-sonnet-4-5")
              )
            )
          )
        )
      )
    )

  test("parse rejects a repeated agent key, naming it and 'appears twice'"):
    SettingsFile.parse(
      "codingAgent = codex\ncodingAgent = claude\n",
      SettingsScope.Project
    ) match
      case Left(problem) =>
        assert(
          problem.message.contains("appears twice"),
          s"should say the key appears twice: ${problem.message}"
        )
        assert(
          problem.message.contains("codingAgent"),
          s"should name the key: ${problem.message}"
        )
      case Right(settings) => fail(s"expected a parse error, got: $settings")

  test("parse rejects an agent value naming an unsupported harness"):
    SettingsFile.parse("codingAgent = mistral\n", SettingsScope.Project) match
      case Left(problem) =>
        AgentSpec.harnessNames.keys.foreach: valid =>
          assert(
            problem.message.contains(valid),
            s"should list valid harness `$valid`: ${problem.message}"
          )
      case Right(settings) => fail(s"expected a parse error, got: $settings")

  test("parse treats an empty agent value as absent, like stack keys"):
    assertEquals(
      SettingsFile.parse("codingAgent =\n", SettingsScope.Project),
      Right(ParsedSettings(StackSettings.empty, AgentSettings.empty))
    )

  test("parse in UserGlobal scope rejects stack keys as project-only"):
    SettingsFile.parse("format = cargo fmt\n", SettingsScope.UserGlobal) match
      case Left(problem) =>
        assert(
          problem.message.contains("format"),
          s"should name the key: ${problem.message}"
        )
        assert(
          problem.message.contains("project"),
          s"should say stack commands are per-project: ${problem.message}"
        )
      case Right(settings) => fail(s"expected a parse error, got: $settings")

  test("parse in UserGlobal scope accepts agent keys"):
    assertEquals(
      SettingsFile.parse("codingAgent = codex\n", SettingsScope.UserGlobal),
      Right(
        ParsedSettings(
          StackSettings.empty,
          AgentSettings(coding = Some(AgentSpec(BackendTag.Codex, None)))
        )
      )
    )

  test("parse in Project scope accepts both stack and agent keys together"):
    assertEquals(
      SettingsFile.parse(
        "format = cargo fmt\ncodingAgent = codex\nformat = pnpm exec prettier --write .\n",
        SettingsScope.Project
      ),
      Right(
        ParsedSettings(
          StackSettings(format =
            List("cargo fmt", "pnpm exec prettier --write .")
          ),
          AgentSettings(coding = Some(AgentSpec(BackendTag.Codex, None)))
        )
      )
    )

  test("hasStackLines is true for a live stack key"):
    assert(SettingsFile.hasStackLines("format = cargo fmt"))

  test("hasStackLines is true for a commented-out unset stack key"):
    assert(SettingsFile.hasStackLines("# format =   (no formatter found)"))

  test("hasStackLines is true for a commented-out demoted stack key"):
    assert(
      SettingsFile.hasStackLines("# lint = just check   (just: not found)")
    )

  test("hasStackLines is false for a file naming only agent keys"):
    val content =
      """# orca settings — edit freely, commit with the project.
        |codingAgent = codex
        |""".stripMargin
    assert(!SettingsFile.hasStackLines(content))

  test(
    "hasStackLines is false for the rendered Header (stack words appear " +
      "only in prose, never at key position)"
  ):
    // The Header's second line names format/lint/test in prose with no
    // `=` — a discovery-written header alone must never suppress a
    // re-discovery trigger.
    assert(!SettingsFile.hasStackLines(SettingsFile.Header))

  test("hasStackLines is false for the empty string"):
    assert(!SettingsFile.hasStackLines(""))

  test("render pins the file format: header, own-line comments, unset"):
    val entries = List(
      SettingsEntry.Command(
        "format",
        "cargo fmt",
        Some("Cargo.toml (rustfmt ships with the toolchain)")
      ),
      SettingsEntry.Command(
        "lint",
        "cargo check --tests",
        Some("compiles main+test code, runs nothing")
      ),
      SettingsEntry.Unset("test", "no test evidence found")
    )
    assertEquals(
      SettingsFile.render(entries),
      """# orca settings — edit freely, commit with the project.
        |# Delete the stack lines (format/lint/test, commented ones too) to re-run auto-discovery.
        |# Cargo.toml (rustfmt ships with the toolchain)
        |format = cargo fmt
        |# compiles main+test code, runs nothing
        |lint = cargo check --tests
        |# test =   (no test evidence found)
        |""".stripMargin
    )

  test("render pins the Demoted shape, collapsing whitespace runs"):
    val rendered = SettingsFile.render(
      List(
        SettingsEntry.Demoted(
          "lint",
          "just \ncheck",
          "just: not\n  found on PATH"
        )
      )
    )
    assert(
      rendered.endsWith("\n# lint = just check   (just: not found on PATH)\n"),
      s"a demoted entry must render as a commented-out command with its " +
        s"reason, whitespace runs collapsed, got: $rendered"
    )

  test("render turns a multi-line comment into # lines that parse ignores"):
    val rendered = SettingsFile.render(
      List(
        SettingsEntry.Command(
          "format",
          "cargo fmt",
          Some("Cargo.toml\nCI runs it in ci.yml")
        )
      )
    )
    assert(
      rendered.contains(
        "# Cargo.toml\n# CI runs it in ci.yml\nformat = cargo fmt"
      ),
      s"each comment line should render as its own # line, got: $rendered"
    )
    assertEquals(
      SettingsFile.parse(rendered, SettingsScope.Project).map(_.stack),
      Right(StackSettings(format = List("cargo fmt")))
    )
