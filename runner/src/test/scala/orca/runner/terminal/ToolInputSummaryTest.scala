package orca.runner.terminal

class ToolInputSummaryTest extends munit.FunSuite:

  private val maxLen = 120

  test("summarise returns empty string for empty or `{}` input"):
    assertEquals(ToolInputSummary.summarise("", maxLen), "")
    assertEquals(ToolInputSummary.summarise("{}", maxLen), "")

  test("summarise picks file_path as the headline when present"):
    val raw = """{"file_path":"/tmp/foo.txt","other":"x"}"""
    assertEquals(ToolInputSummary.summarise(raw, maxLen), "(/tmp/foo.txt)")

  test("summarise relativises a file_path under workDir"):
    val workDir = os.Path("/tmp/orca-AbC")
    val raw = s"""{"file_path":"${workDir.toString}/src/Main.scala"}"""
    val out = ToolInputSummary.summarise(raw, maxLen, Some(workDir))
    assertEquals(out, "(src/Main.scala)")

  test("summarise leaves a file_path outside workDir absolute"):
    val workDir = os.Path("/tmp/orca-AbC")
    val raw = """{"file_path":"/etc/hosts"}"""
    val out = ToolInputSummary.summarise(raw, maxLen, Some(workDir))
    assertEquals(out, "(/etc/hosts)")

  test("summarise returns `.` when file_path equals workDir exactly"):
    val workDir = os.Path("/tmp/orca-AbC")
    val raw = s"""{"file_path":"${workDir.toString}"}"""
    val out = ToolInputSummary.summarise(raw, maxLen, Some(workDir))
    assertEquals(out, "(.)")

  test("summarise extracts a field written with a space after the colon"):
    val raw = """{"file_path": "/tmp/foo.txt"}"""
    assertEquals(ToolInputSummary.summarise(raw, maxLen), "(/tmp/foo.txt)")

  test("summarise skips an occurrence of a headline name used as a value"):
    val raw = """{"query":"file_path","file_path":"real.py"}"""
    assertEquals(ToolInputSummary.summarise(raw, maxLen), "(real.py)")

  test("summarise picks the rev/skill/name headlines"):
    assertEquals(
      ToolInputSummary.summarise("""{"rev":"HEAD","stat":true}""", maxLen),
      "(HEAD)"
    )
    assertEquals(
      ToolInputSummary.summarise("""{"skill":"tdd","args":"go"}""", maxLen),
      "(tdd)"
    )
    assertEquals(
      ToolInputSummary.summarise("""{"name":"reviewer"}""", maxLen),
      "(reviewer)"
    )

  test("summarise names the fields of an input with no headline field"):
    val raw = """{"old_string":"a\nb","new_string":"c\nd"}"""
    assertEquals(
      ToolInputSummary.summarise(raw, maxLen),
      "{old_string, new_string}"
    )

  test("summarise lists only top-level field names, not nested ones"):
    val raw = """{"edits":[{"inner":"x"}],"dry_run":true}"""
    assertEquals(ToolInputSummary.summarise(raw, maxLen), "{edits, dry_run}")

  test("summarise strips the shell wrapper from a command headline"):
    val raw = """{"command":"/bin/bash -lc \"rtk git status --short\""}"""
    assertEquals(
      ToolInputSummary.summarise(raw, maxLen),
      "(rtk git status --short)"
    )

  test("summarise unwraps a command that toggles quoting mid-word"):
    val raw =
      """{"command":"/bin/bash -lc \"rg --files -g '\"'!*.pyc'\"' | head\""}"""
    assertEquals(
      ToolInputSummary.summarise(raw, maxLen),
      """(rg --files -g '"'!*.pyc'"' | head)"""
    )

  test("summarise cuts a long command at a token boundary"):
    val command = List.fill(40)("token").mkString(" ")
    val raw = s"""{"command":"$command"}"""
    val out = ToolInputSummary.summarise(raw, maxLen)
    assert(out.endsWith("token…)"), s"cut mid-token; got: $out")
    assert(out.length <= maxLen + 2, s"headline exceeded the cap; got: $out")

  test("summarise leaves command/pattern/query unchanged when they look pathy"):
    val workDir = os.Path("/tmp/orca-AbC")
    val raw = s"""{"command":"ls ${workDir.toString}/src"}"""
    val out = ToolInputSummary.summarise(raw, maxLen, Some(workDir))
    // `command` is free-form text; we don't try to relativise paths
    // embedded in shell commands.
    assert(
      out.contains(workDir.toString),
      s"expected absolute path preserved in command field; got: $out"
    )
