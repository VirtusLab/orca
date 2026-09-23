package orca.settings

import munit.FunSuite

class StackCommandTest extends FunSuite:

  test("from collapses newlines and trims"):
    assertEquals(
      StackCommand.from("  cargo fmt\n  --all \n").map(_.value),
      Right("cargo fmt --all")
    )

  test("from rejects a blank value"):
    assertEquals(StackCommand.from(" \n "), Left(StackCommand.Invalid.Blank))

  test("from rejects a value that starts with # after collapsing"):
    assertEquals(
      StackCommand.from("\n# cargo fmt"),
      Left(StackCommand.Invalid.CommentedOut)
    )

  test("from rejects the reserved `off`"):
    assertEquals(StackCommand.from("off"), Left(StackCommand.Invalid.Disable))
