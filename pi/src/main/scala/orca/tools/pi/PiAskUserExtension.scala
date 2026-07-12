package orca.tools.pi

import scala.util.control.NonFatal

/** Temporary Pi extension that exposes Orca's backend-agnostic `ask_user`
  * conversation event through Pi's native extension UI protocol.
  *
  * The extension intentionally has no imports so it can be written to a temp
  * directory and loaded by Pi without relying on Node module resolution from
  * that directory. The `parameters` value is plain JSON Schema / TypeBox shape,
  * which Pi accepts for tool schemas.
  */
private[pi] final class PiAskUserExtension private (
    val dir: os.Path,
    val file: os.Path
) extends AutoCloseable:
  def close(): Unit =
    try os.remove.all(dir)
    catch case NonFatal(_) => ()

private[pi] object PiAskUserExtension:

  val ToolName: String = "ask_user"

  val Hint: String =
    "If you need a concise clarification from the human before continuing, " +
      s"call the `$ToolName` tool with a clear question. Use it sparingly; " +
      "do not ask if you can make a reasonable assumption."

  def allocate(): PiAskUserExtension =
    val dir = os.temp.dir(prefix = "orca-pi-ask-user-", deleteOnExit = true)
    val file = dir / "ask-user.ts"
    os.write(file, loadSource().replace("__TOOL_NAME__", ToolName))
    new PiAskUserExtension(dir, file)

  private def loadSource(): String =
    val stream = getClass.getResourceAsStream("/orca/tools/pi/ask-user.ts")
    require(
      stream != null,
      "ask-user.ts resource missing from the pi module jar"
    )
    try String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    finally stream.close()
