package orca.shell.ui

class ConsoleUiShellTest extends munit.FunSuite:

  test("uniqueIds leaves already-unique labels untouched"):
    assertEquals(
      ConsoleUiShell.uniqueIds(List("claude", "codex", "gemini")),
      List("claude", "codex", "gemini")
    )

  test("uniqueIds suffixes every repeat of a duplicate label with a counter"):
    assertEquals(
      ConsoleUiShell.uniqueIds(List("claude", "claude", "codex", "claude")),
      List("claude", "claude#1", "codex", "claude#2")
    )

  test("uniqueIds keeps distinct duplicate groups independent"):
    assertEquals(
      ConsoleUiShell.uniqueIds(List("a", "b", "a", "b", "b")),
      List("a", "b", "a#1", "b#1", "b#2")
    )
