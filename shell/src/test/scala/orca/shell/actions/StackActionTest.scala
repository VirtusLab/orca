package orca.shell.actions

import orca.StackSettings

class StackActionTest extends munit.FunSuite:

  test(
    "renderStackSettings lists each non-empty key in format/lint/test order"
  ):
    assertEquals(
      StackAction.renderStackSettings(
        StackSettings(
          format = List("cargo fmt"),
          lint = List("cargo check --tests"),
          test = List("cargo test")
        )
      ),
      "  format: cargo fmt\n  lint: cargo check --tests\n  test: cargo test"
    )

  test("renderStackSettings notes when there are no live commands"):
    assert(
      StackAction
        .renderStackSettings(StackSettings.empty)
        .contains("no live commands")
    )
