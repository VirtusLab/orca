package orca.runner

import orca.lifecycle.DirtyTreeChoice
class DirtyTreePromptTest extends munit.FunSuite:

  test("the production prompt asks on stderr, reads stdin, keeps stdout clean"):
    val out = new java.io.ByteArrayOutputStream()
    val err = new java.io.ByteArrayOutputStream()
    val choice = Console.withIn(new java.io.StringReader("2\n")):
      Console.withOut(out):
        Console.withErr(err):
          DirtyTreePrompt.promptOnStderr(dirtyCount = 2)
    assertEquals(choice, DirtyTreeChoice.Keep)
    val menu = err.toString("UTF-8")
    assert(menu.contains("2 uncommitted/untracked file(s)"), menu)
    assert(
      menu.contains("1)") && menu.contains("2)") && menu.contains("3)"),
      s"all three options must be offered: $menu"
    )
    assert(
      menu.contains("kept changes to tracked files are lost"),
      s"the keep option must warn about the failure-teardown data loss: $menu"
    )
    assertEquals(out.toString("UTF-8"), "", "stdout must stay clean")

  test("the production prompt stashes when stdin is at EOF"):
    val choice = Console.withIn(new java.io.StringReader("")):
      Console.withErr(new java.io.ByteArrayOutputStream()):
        DirtyTreePrompt.promptOnStderr(dirtyCount = 1)
    assertEquals(choice, DirtyTreeChoice.Stash)

  test(
    "the production prompt re-asks an unrecognized answer, then honors the next valid one"
  ):
    val err = new java.io.ByteArrayOutputStream()
    val choice = Console.withIn(new java.io.StringReader("keep\n2\n")):
      Console.withErr(err):
        DirtyTreePrompt.promptOnStderr(dirtyCount = 1)
    assertEquals(choice, DirtyTreeChoice.Keep)
    val transcript = err.toString("UTF-8")
    assert(
      transcript.contains("unrecognized answer"),
      s"the re-ask must say why it is asking again: $transcript"
    )
