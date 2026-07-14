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
        assert(problem.contains("line 2"), s"should name the line: $problem")
        assert(
          problem.contains("key = value"),
          s"should name the expected shape: $problem"
        )
      case Right(settings) => fail(s"expected a parse error, got: $settings")

  test("parse rejects an unknown key, naming it and the valid keys"):
    SettingsFile.parse("fromat = cargo fmt\n") match
      case Left(problem) =>
        assert(problem.contains("fromat"), s"should name the key: $problem")
        SettingsFile.ValidKeys.foreach: valid =>
          assert(
            problem.contains(valid),
            s"should list valid key `$valid`: $problem"
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
        assert(problem.contains("Format"), s"should name the key: $problem")
      case Right(settings) => fail(s"expected a parse error, got: $settings")

  test("render output parses back to the entries' commands".ignore):
    fail("pending: implemented with the writer")
