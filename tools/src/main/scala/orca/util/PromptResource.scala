package orca.util

/** Loads prompt templates from classpath resources, one `.md` file per template
  * under `src/main/resources/<pkg>/prompts/<name>.md`.
  *
  * Templates use `{{name}}` placeholders. Dynamic values go through [[render]];
  * static fragments shared between templates should be substituted once at
  * object initialization.
  *
  * Missing resources fail fast as `RuntimeException` at object-init time.
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
    * -> value)` pairs. Unreferenced substitutions are ignored; a placeholder
    * with no substitution is a defect — a template and its call site that have
    * drifted apart — and throws rather than sending the literal `{{name}}` to
    * an agent.
    *
    * Only `template` is scanned, never the result: a substituted value can
    * itself hold `{{…}}` (a diff of a prompt file under review does), and that
    * is content, not a placeholder.
    */
  def render(template: String, substitutions: (String, String)*): String =
    val supplied = substitutions.map((key, _) => key).toSet
    val unfilled = Placeholder
      .findAllMatchIn(template)
      .map(_.group(1))
      .filterNot(supplied.contains)
      .distinct
      .toList
    if unfilled.nonEmpty then
      throw new RuntimeException(
        s"prompt template placeholders with no substitution: " +
          unfilled.mkString(", ")
      )
    substitutions.foldLeft(template):
      case (acc, (key, value)) => acc.replace(s"{{$key}}", value)

  private val Placeholder = """\{\{(\w+)\}\}""".r
