package orca.util

/** Loads prompt templates from classpath resources. The convention is one `.md`
  * file per template, placed under `src/main/resources/<pkg>/prompts/<name>.md`
  * — keeping the prose out of `.scala` files makes the text easier to edit (no
  * `.stripMargin` margins, no double-escaped quotes) and treats prompts as the
  * user-facing artifacts they are.
  *
  * Templates use `{{name}}` placeholders. Dynamic values supplied at call time
  * go through [[render]]; static fragments shared between templates (e.g. a
  * common rules block) should be substituted once at object initialization to
  * keep per-call cost minimal.
  *
  * Missing-resource failures surface as `RuntimeException` at object-init time
  * — desirable fail-fast behaviour for what is effectively a packaging mistake.
  */
private[orca] object PromptResource:

  /** Read a classpath resource as UTF-8 text. The path is absolute relative to
    * the classpath root (use a leading `/`).
    */
  def load(path: String): String =
    val stream = Option(getClass.getResourceAsStream(path)).getOrElse(
      throw new RuntimeException(
        s"prompt resource not found on classpath: $path"
      )
    )
    try scala.io.Source.fromInputStream(stream, "UTF-8").mkString
    finally stream.close()

  /** Substitute `{{name}}` placeholders in `template` with the supplied `(name
    * -> value)` pairs. Unknown placeholders are left intact; unreferenced
    * substitutions are ignored.
    */
  def render(template: String, substitutions: (String, String)*): String =
    substitutions.foldLeft(template):
      case (acc, (key, value)) => acc.replace(s"{{$key}}", value)
