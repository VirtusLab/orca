package orca.backend.pi

import orca.effect.FileSystem
import ox.supervised

class PiAskUserExtensionTest extends munit.FunSuite:

  test("write() writes an extension file with the tool name substituted"):
    val content =
      supervised(os.read(PiAskUserExtension.write(FileSystem.ambient)))
    assert(content.contains("registerTool"), content)
    assert(
      content.contains(s"""name: "${PiAskUserExtension.ToolName}""""),
      content
    )
    assert(!content.contains("__TOOL_NAME__"), content)
