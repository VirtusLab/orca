package orca.settings

import munit.FunSuite

class StackValueTest extends FunSuite:

  test("parse collapses newlines and trims a command"):
    StackValue.parse("  cargo fmt\n  --all \n") match
      case StackValue.Run(command) =>
        assertEquals(command.value, "cargo fmt --all")
      case other => fail(s"expected a command, got $other")

  test("parse reads a blank value as Empty"):
    assertEquals(StackValue.parse(" \n "), StackValue.Empty)

  test("parse reads a value starting with # as CommentedOut"):
    assertEquals(StackValue.parse("\n# cargo fmt"), StackValue.CommentedOut)

  test("parse reads `off` as Off"):
    assertEquals(StackValue.parse("off"), StackValue.Off)
