package orca.util

class PromptResourceTest extends munit.FunSuite:

  test("a placeholder with no substitution fails the render"):
    // Without this a half-finished rename between a template and its call site
    // sends the literal `{{name}}` to an agent, with every test still green.
    val thrown = intercept[RuntimeException]:
      val _ = PromptResource.render("a {{one}} b {{two}}", "one" -> "1")
    assert(thrown.getMessage.contains("two"), thrown.getMessage)

  test("a `{{…}}` inside a substituted value is content, not a placeholder"):
    // A reviewer's diff of a prompt file carries the file's own placeholders.
    assertEquals(
      PromptResource.render("diff: {{body}}", "body" -> "+{{openFindings}}"),
      "diff: +{{openFindings}}"
    )
