package orca.tools.pi

import orca.backend.TurnResources
import ox.ResourceScope

/** Temporary Pi extension exposing Orca's `ask_user` turn event through Pi's
  * native extension UI protocol.
  *
  * The extension deliberately has no imports, so it can be written to a temp
  * directory and loaded without Node module resolution from there.
  */
private[pi] object PiAskUserExtension:

  val ToolName: String = "ask_user"

  val Hint: String =
    "If you need a concise clarification from the human before continuing, " +
      s"call the `$ToolName` tool with a clear question. Use it sparingly; " +
      "do not ask if you can make a reasonable assumption."

  /** Write the extension to a temp file removed when the turn ends. */
  def write()(using ResourceScope): os.Path =
    val file = TurnResources.tempDir("orca-pi-ask-user-") / "ask-user.ts"
    os.write(file, loadSource().replace("__TOOL_NAME__", ToolName))
    file

  private def loadSource(): String =
    val stream = getClass.getResourceAsStream("/orca/tools/pi/ask-user.ts")
    require(
      stream != null,
      "ask-user.ts resource missing from the pi module jar"
    )
    try String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally stream.close()
