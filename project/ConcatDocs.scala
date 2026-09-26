import sbt.*

/** Joins the documentation pages under `docs/` into one Markdown file, in the
  * order `docs/index.md`'s toctrees list them, each page preceded by a heading
  * naming its path. Pages the toctrees do not list are left out, so the
  * bundle matches the published site.
  */
object ConcatDocs {
  private val entry = """^\s{3}([\w./-]+)\s*$""".r

  def apply(docsDir: File): String = {
    val index = IO.read(docsDir / "index.md")
    val pages = index.linesIterator.collect { case entry(path) => path }.toList
    val intro = index.split("```\\{eval-rst\\}").head.trim
    val body = pages.map { page =>
      val f = docsDir / s"$page.md"
      s"<!-- docs/$page.md -->\n\n" + IO.read(f).trim
    }
    (intro :: body).mkString("\n\n---\n\n") + "\n"
  }
}
