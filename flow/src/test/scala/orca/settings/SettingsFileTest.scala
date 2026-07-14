package orca.settings

import munit.FunSuite
import orca.StackSettings

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
      SettingsFile.parse(content),
      Right(StackSettings(format = List("cargo fmt")))
    )

  test("parse rejects a non-comment line without =, naming line and shape"):
    SettingsFile.parse("format = cargo fmt\ncargo test\n") match
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
    SettingsFile.parse("fromat = cargo fmt\n") match
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
    SettingsFile.parse("lint = # disabled\n") match
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
      SettingsFile.parse("lint = FOO=bar cargo check\n"),
      Right(StackSettings(lint = List("FOO=bar cargo check")))
    )

  test("parse silently drops a key whose value is empty after trimming"):
    assertEquals(
      SettingsFile.parse("format =   \ntest = cargo test\n"),
      Right(StackSettings(test = List("cargo test")))
    )

  test("parse appends repeated keys in file order"):
    assertEquals(
      SettingsFile.parse(
        "format = cargo fmt\nformat = pnpm exec prettier --write .\n"
      ),
      Right(
        StackSettings(format =
          List("cargo fmt", "pnpm exec prettier --write .")
        )
      )
    )

  test("parse treats keys as case-sensitive, rejecting a capitalised key"):
    SettingsFile.parse("Format = cargo fmt\n") match
      case Left(problem) =>
        assert(
          problem.message.contains("Format"),
          s"should name the key: ${problem.message}"
        )
      case Right(settings) => fail(s"expected a parse error, got: $settings")

  test("parse keeps a bare # inside the value (mid-line # is command text)"):
    assertEquals(
      SettingsFile.parse("format = echo '#1'\n"),
      Right(StackSettings(format = List("echo '#1'")))
    )

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
      """# orca stack settings — edit freely, commit with the project.
        |# Delete this file to re-run auto-discovery.
        |# Cargo.toml (rustfmt ships with the toolchain)
        |format = cargo fmt
        |# compiles main+test code, runs nothing
        |lint = cargo check --tests
        |# test =   (no test evidence found)
        |""".stripMargin
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
      SettingsFile.parse(rendered),
      Right(StackSettings(format = List("cargo fmt")))
    )

  test("render output parses back to the entries' commands"):
    val entries = List(
      SettingsEntry.Command(
        "format",
        "cargo fmt",
        Some("Cargo.toml (rustfmt ships with the toolchain)")
      ),
      SettingsEntry.Command("lint", "cargo check --tests", None),
      SettingsEntry.Command("lint", "pnpm run lint", Some("package.json")),
      SettingsEntry.Unset("test", "no test evidence found")
    )
    assertEquals(
      SettingsFile.parse(SettingsFile.render(entries)),
      Right(
        StackSettings(
          format = List("cargo fmt"),
          lint = List("cargo check --tests", "pnpm run lint")
        )
      )
    )
