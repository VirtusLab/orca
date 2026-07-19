package orca.shell.ui

import java.io.{ByteArrayOutputStream, PrintStream}

class ShellOutTest extends munit.FunSuite:

  private def captured(body: => Unit): String =
    val out = ByteArrayOutputStream()
    Console.withOut(PrintStream(out))(body)
    out.toString

  test("say prints the message behind the shell's glyph"):
    assertEquals(captured(ShellOut.say("hello")), "◆ hello\n")

  test("say carries the message through unchanged, glyph aside"):
    assertEquals(
      captured(ShellOut.say("flow demo.sc finished (exit 0)")),
      "◆ flow demo.sc finished (exit 0)\n"
    )
