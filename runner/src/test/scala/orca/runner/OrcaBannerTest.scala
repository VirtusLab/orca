package orca.runner

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8

class OrcaBannerTest extends munit.FunSuite:

  private def printed(trace: Option[os.Path]): String =
    val bytes = ByteArrayOutputStream()
    val out = PrintStream(bytes, true, UTF_8)
    OrcaBanner.print(
      out,
      progress = os.root / "p.json",
      events = os.root / "e.jsonl",
      trace = trace
    )
    bytes.toString(UTF_8)

  test("prints the version, progress, events and trace lines"):
    assertEquals(
      printed(Some(os.root / "t.log")),
      s"""Orca ${OrcaBanner.version}
         |  progress: /p.json
         |  events:   /e.jsonl
         |  trace:    /t.log
         |
         |""".stripMargin
    )

  test("prints a placeholder when the trace file is unavailable"):
    assert(
      printed(None).contains("  trace:    (trace file unavailable)\n"),
      printed(None)
    )
