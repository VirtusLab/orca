package flowtests

/** An opaque type a flow script reaches through the `orca` package must stay
  * opaque: its companion's members may not hand out the underlying type.
  */
class ExportedOpaqueTypesTest extends munit.FunSuite:

  private def assertMismatch(errors: String, required: String): Unit =
    assert(errors.contains(s"Required: $required"), errors)

  test("ReviewerSlug does not widen to String"):
    assertMismatch(
      compileErrors("""val s: String = orca.ReviewerSlug("x")"""),
      "String"
    )

  test("Title does not widen to String"):
    assertMismatch(
      compileErrors("""val s: String = orca.Title("x")"""),
      "String"
    )

  test("Model does not widen to String"):
    assertMismatch(
      compileErrors("""val s: String = orca.Model("x")"""),
      "String"
    )

  test("OpencodeLauncher does not widen to Seq[String]"):
    assertMismatch(
      compileErrors("val s: Seq[String] = orca.OpencodeLauncher.default"),
      "Seq[String]"
    )
