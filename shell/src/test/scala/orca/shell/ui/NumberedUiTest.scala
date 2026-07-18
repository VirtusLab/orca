package orca.shell.ui

import java.io.{BufferedReader, ByteArrayOutputStream, PrintStream, StringReader}

class NumberedUiTest extends munit.FunSuite:

  private def uiOf(input: String): (NumberedUi, ByteArrayOutputStream) =
    val out = ByteArrayOutputStream()
    val ui = NumberedUi(BufferedReader(StringReader(input)), PrintStream(out))
    (ui, out)

  private val choices = List(
    Choice(1, "First"),
    Choice(2, "Second", disabledReason = Some("no manifests")),
    Choice(3, "Third")
  )

  test("select renders numbered rows, disabled row shown with its reason"):
    val (ui, out) = uiOf("1\n")
    val _ = ui.select("Pick one", choices)
    val rendered = out.toString
    assert(rendered.contains("1. First"))
    assert(rendered.contains("2. Second"))
    assert(rendered.contains("no manifests"))
    assert(rendered.contains("3. Third"))

  test("select accepts a numeric line and returns the matching value"):
    val (ui, _) = uiOf("2\n")
    // choice 2 is disabled, so "2" alone must re-prompt into EOF -> Cancelled
    assertEquals(ui.select("Pick one", choices), UiOutcome.Cancelled)

  test("select returns the value of an enabled choice"):
    val (ui, _) = uiOf("3\n")
    assertEquals(ui.select("Pick one", choices), UiOutcome.Selected(3))

  test("select re-prompts on garbage then accepts a later valid line"):
    val (ui, out) = uiOf("nope\n1\n")
    assertEquals(ui.select("Pick one", choices), UiOutcome.Selected(1))
    assert(out.toString.toLowerCase.contains("not a valid choice"))

  test("select re-prompts when the chosen row is disabled, same as garbage"):
    val (ui, _) = uiOf("2\n1\n")
    assertEquals(ui.select("Pick one", choices), UiOutcome.Selected(1))

  test("select returns Cancelled on EOF"):
    val (ui, _) = uiOf("")
    assertEquals(ui.select("Pick one", choices), UiOutcome.Cancelled)

  test("confirm accepts an empty line as the default"):
    val (ui, _) = uiOf("\n")
    assertEquals(ui.confirm("Continue?", default = true), UiOutcome.Selected(true))

  test("confirm accepts an empty line as the default (false)"):
    val (ui, _) = uiOf("\n")
    assertEquals(ui.confirm("Continue?", default = false), UiOutcome.Selected(false))

  test("confirm accepts y/n and re-prompts on garbage"):
    val (ui, _) = uiOf("what\nn\n")
    assertEquals(ui.confirm("Continue?", default = true), UiOutcome.Selected(false))

  test("confirm returns Cancelled on EOF"):
    val (ui, _) = uiOf("")
    assertEquals(ui.confirm("Continue?", default = true), UiOutcome.Cancelled)

  test("input returns the default on an empty line when a default is set"):
    val (ui, _) = uiOf("\n")
    assertEquals(ui.input("Name", default = Some("orca")), UiOutcome.Selected("orca"))

  test("input returns the typed line when non-empty"):
    val (ui, _) = uiOf("hello\n")
    assertEquals(ui.input("Name", default = Some("orca")), UiOutcome.Selected("hello"))

  test("input returns Cancelled on EOF"):
    val (ui, _) = uiOf("")
    assertEquals(ui.input("Name"), UiOutcome.Cancelled)
