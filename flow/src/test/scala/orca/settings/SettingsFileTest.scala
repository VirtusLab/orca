package orca.settings

import munit.FunSuite
import orca.StackSettings
import orca.agents.BackendTag

class SettingsFileTest extends FunSuite:

  private def command(raw: String): StackCommand =
    StackValue.parse(raw) match
      case StackValue.Run(command) => command
      case other                   => fail(s"not a command: $other")

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
      Right(Some(StackSettings(format = List("cargo fmt"))))
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

  test("parse rejects an agent value whose first non-space char is #"):
    assertEquals(
      SettingsFile.parse("codingAgent = # codex\n", SettingsScope.Project),
      Left(SettingsError.CommentedValue(1, "codingAgent"))
    )

  test("parse takes the value verbatim after the first =, keeping embedded ="):
    assertEquals(
      SettingsFile
        .parse("lint = FOO=bar cargo check\n", SettingsScope.Project)
        .map(_.stack),
      Right(Some(StackSettings(lint = List("FOO=bar cargo check"))))
    )

  test("parse silently drops a key whose value is empty after trimming"):
    assertEquals(
      SettingsFile
        .parse("format =   \ntest = cargo test\n", SettingsScope.Project)
        .map(_.stack),
      Right(Some(StackSettings(test = List("cargo test"))))
    )

  test("parse leaves the stack unconfigured when its only value is empty"):
    assertEquals(
      SettingsFile.parse("format =\n", SettingsScope.Project).map(_.stack),
      Right(None)
    )

  test("parse treats a stack key's value of `off` as explicitly disabled"):
    assertEquals(
      SettingsFile
        .parse(
          "format = off\nlint = off\ntest = off\n",
          SettingsScope.Project
        )
        .map(_.stack),
      Right(Some(StackSettings.empty)),
      "`off` configures the key with no commands"
    )

  test("parse never lets `off` join the command list alongside a real one"):
    assertEquals(
      SettingsFile
        .parse("format = cargo fmt\nformat = off\n", SettingsScope.Project)
        .map(_.stack.map(_.format)),
      Right(Some(List("cargo fmt"))),
      "off must not appear as a literal shell command in the resolved settings"
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
        Some(
          StackSettings(format =
            List("cargo fmt", "pnpm exec prettier --write .")
          )
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
      Right(Some(StackSettings(format = List("echo '#1'"))))
    )

  test("parse recognizes codingAgent against a bare harness name"):
    assertEquals(
      SettingsFile.parse("codingAgent = codex\n", SettingsScope.Project),
      Right(
        ParsedSettings(
          None,
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
          None,
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
          None,
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
      Right(ParsedSettings(None, AgentSettings.empty))
    )

  test("parse in UserGlobal scope rejects stack keys, even with no value"):
    SettingsFile.parse("format =\n", SettingsScope.UserGlobal) match
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
          None,
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
          Some(
            StackSettings(format =
              List("cargo fmt", "pnpm exec prettier --write .")
            )
          ),
          AgentSettings(coding = Some(AgentSpec(BackendTag.Codex, None)))
        )
      )
    )

  test("parse leaves the stack unconfigured for commented-out stack keys"):
    assertEquals(
      SettingsFile
        .parse("# format = cargo fmt\n", SettingsScope.Project)
        .map(_.stack),
      Right(None)
    )

  test("parse leaves the stack unconfigured for the rendered Header"):
    // The Header names format/lint/test in prose — a discovery-written header
    // alone must never suppress re-discovery.
    assertEquals(
      SettingsFile
        .parse(SettingsFile.Header, SettingsScope.Project)
        .map(_.stack),
      Right(None)
    )

  test(
    "parse and stripStackLines agree on a stack key with a trailing " +
      "control byte"
  ):
    // `\u001f` is stripped by String.trim (the key trim) but is not matched by
    // a regex `\s`: the strip and the parser must agree it is a `format` key.
    val line = "format\u001f= cargo fmt\n"
    assertEquals(
      SettingsFile
        .parse(line, SettingsScope.Project)
        .map(_.stack.map(_.format)),
      Right(Some(List("cargo fmt")))
    )
    assertEquals(SettingsFile.stripStackLines(line), "")

  test("render pins the file format: header, own-line comments, unset"):
    val entries = List(
      SettingsEntry.Command(
        StackKey.Format,
        command("cargo fmt"),
        Some("Cargo.toml (rustfmt ships with the toolchain)")
      ),
      SettingsEntry.Command(
        StackKey.Lint,
        command("cargo check --tests"),
        Some("compiles main+test code, runs nothing")
      ),
      SettingsEntry.Unset(StackKey.Test, "no test evidence found")
    )
    assertEquals(
      SettingsFile.render(entries),
      """# orca settings — edit freely, commit with the project.
        |# format/lint/test: one shell command per key; `off` disables the gate. Delete the stack lines (or the whole file) to re-run auto-discovery.
        |# planningAgent/codingAgent/reviewAgent (harness[:model]): override the global settings file; a flow's own code overrides both.
        |# Cargo.toml (rustfmt ships with the toolchain)
        |format = cargo fmt
        |# compiles main+test code, runs nothing
        |lint = cargo check --tests
        |# no test evidence found
        |test = off
        |""".stripMargin
    )

  test("render pins the Demoted and Off shapes, collapsing whitespace runs"):
    val rendered = SettingsFile.render(
      List(
        SettingsEntry.Demoted(
          StackKey.Lint,
          "just \ncheck",
          "just: not\n  found on PATH"
        ),
        SettingsEntry.Off(StackKey.Lint)
      )
    )
    assert(
      rendered.endsWith(
        "\n# skipped: lint = just check (just: not found on PATH)\nlint = off\n"
      ),
      s"a demoted entry must render as one comment line, whitespace runs " +
        s"collapsed, and Off as a bare live line, got: $rendered"
    )

  test(
    "stripStackLines drops every live stack line (including explicit off) " +
      "plus its evidence comment, keeping the header, agent keys, and user " +
      "comments"
  ):
    val content =
      """# orca settings — edit freely, commit with the project.
        |# format/lint/test: one shell command per key; `off` disables the gate. Delete the stack lines (or the whole file) to re-run auto-discovery.
        |# planningAgent/codingAgent/reviewAgent (harness[:model]): override the global settings file; a flow's own code overrides both.
        |# Cargo.toml (rustfmt ships with the toolchain)
        |format = cargo fmt
        |# compiles main+test code, runs nothing
        |lint = cargo check --tests
        |# no test evidence found
        |test = off
        |# a user note about coding agent
        |codingAgent = codex
        |# just a regular comment
        |""".stripMargin
    val stripped = SettingsFile.stripStackLines(content)
    assertEquals(
      stripped,
      """# orca settings — edit freely, commit with the project.
        |# format/lint/test: one shell command per key; `off` disables the gate. Delete the stack lines (or the whole file) to re-run auto-discovery.
        |# planningAgent/codingAgent/reviewAgent (harness[:model]): override the global settings file; a flow's own code overrides both.
        |# a user note about coding agent
        |codingAgent = codex
        |# just a regular comment
        |""".stripMargin
    )
    assertEquals(
      SettingsFile.parse(stripped, SettingsScope.Project).map(_.stack),
      Right(None)
    )

  test("stripStackLines drops a trailing skipped line"):
    val content = SettingsFile.render(
      List(
        SettingsEntry
          .Command(StackKey.Test, command("cargo test"), Some("Cargo.toml")),
        SettingsEntry.Demoted(StackKey.Test, "cargo nextest run", "not found")
      )
    )
    assertEquals(
      SettingsFile.stripStackLines(content),
      SettingsFile.Header + "\n"
    )

  test(
    "stripStackLines leaves a commented-out stack-key example untouched " +
      "(comments are inert, never a removal target)"
  ):
    val content =
      """# orca settings — edit freely, commit with the project.
        |# format = cargo fmt
        |codingAgent = codex
        |""".stripMargin
    assertEquals(SettingsFile.stripStackLines(content), content)

  test("stripStackLines leaves a file with no stack lines unchanged"):
    val content =
      """# orca settings — edit freely, commit with the project.
        |codingAgent = codex
        |# a user comment
        |
        |reviewAgent = claude:opus
        |""".stripMargin
    assertEquals(SettingsFile.stripStackLines(content), content)

  test(
    "stripStackLines removes a live stack line directly below the header " +
      "without eating the header itself"
  ):
    val content = SettingsFile.Header + "\nformat = cargo fmt\n"
    assertEquals(
      SettingsFile.stripStackLines(content),
      SettingsFile.Header + "\n"
    )

  test("render turns a multi-line comment into # lines that parse ignores"):
    val rendered = SettingsFile.render(
      List(
        SettingsEntry.Command(
          StackKey.Format,
          command("cargo fmt"),
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
      Right(Some(StackSettings(format = List("cargo fmt"))))
    )

  test("Header documents `off` and the role-agent precedence"):
    assert(
      SettingsFile.Header.contains("`off`"),
      s"the header must mention off: ${SettingsFile.Header}"
    )
    assert(
      SettingsFile.Header.contains("override the global settings file"),
      s"the header must state the precedence: ${SettingsFile.Header}"
    )

  test(
    "a fully-unset discovery render does not re-trigger discovery " +
      "(every task becomes a live `off` line)"
  ):
    val rendered = SettingsFile.render(
      List(
        SettingsEntry.Unset(StackKey.Format, "no formatter found"),
        SettingsEntry.Demoted(StackKey.Lint, "just check", "just: not found"),
        SettingsEntry.Off(StackKey.Lint),
        SettingsEntry.Unset(StackKey.Test, "no test evidence found")
      )
    )
    assertEquals(
      SettingsFile.parse(rendered, SettingsScope.Project).map(_.stack),
      Right(Some(StackSettings.empty))
    )
