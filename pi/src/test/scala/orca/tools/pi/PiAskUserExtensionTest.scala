package orca.tools.pi

class PiAskUserExtensionTest extends munit.FunSuite:

  test("allocate() writes an extension file with the tool name substituted"):
    val extension = PiAskUserExtension.allocate()
    try
      val content = os.read(extension.file)
      assert(content.contains("registerTool"), content)
      assert(
        content.contains(s"""name: "${PiAskUserExtension.ToolName}""""),
        content
      )
      assert(!content.contains("__TOOL_NAME__"), content)
    finally extension.close()
