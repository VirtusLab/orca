package orca.backend.pi

import orca.effect.FileSystem
import orca.safe.prelude.given
import orca.util.PromptResource
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
  def write(fs: FileSystem)(using ResourceScope): os.Path =
    val file = fs.tempDir("orca-pi-ask-user-") / "ask-user.ts"
    fs.writeNew(file, source.replace("__TOOL_NAME__", ToolName))
    file

  private def source: String =
    PromptResource.load("/orca/backend/pi/ask-user.ts")
