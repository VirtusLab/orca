import sbt.*

import java.util.regex.{Matcher, Pattern}

/** Bumps scala-cli `using dep` coordinates and `using scala` pins in docs as
  * part of a release.
  *
  * Two independent rewrites run over the same file set:
  *
  *   - `<organization>::<module>:<version>` — the form Orca's flow scripts and
  *     READMEs use for the library coordinate. Complement to the stock
  *     `com.softwaremill.UpdateVersionInDocs`, which is hardcoded to the sbt
  *     build coordinate `"org" %% "name" % "version"`; the two are typically
  *     chained from the `updateDocs` task so either form gets bumped.
  *   - `//> using scala <version>` — the Scala version consumers must pin to
  *     (kept equal to the build's own `V.scala`, since published TASTy only
  *     resolves on a Scala version at or above the one it was compiled with).
  *
  * Each entry in `filesToUpdate` is either a file or a directory; directories
  * are walked recursively and `.md` / `.sc` files are rewritten in place.
  * Returns the files that actually changed so the release driver can stage them
  * with `git add`.
  */
object UpdateScalaCliVersionInDocs {
  def apply(
      log: Logger,
      organization: String,
      version: String,
      scalaVersion: String,
      filesToUpdate: List[File]
  ): Seq[File] = {
    val orgQuoted = Pattern.quote(organization)
    val versionRegex = s"""($orgQuoted::[\\w-]+:)([\\w\\.-]+)""".r
    val scalaVersionRegex = """(//> using scala )([\w.-]+)""".r

    def rewrite(f: File): Option[File] = {
      val before = IO.read(f)
      val afterCoords = versionRegex.replaceAllIn(
        before,
        m => Matcher.quoteReplacement(m.group(1) + version)
      )
      val after = scalaVersionRegex.replaceAllIn(
        afterCoords,
        m => Matcher.quoteReplacement(m.group(1) + scalaVersion)
      )
      if (after != before) {
        log.info(
          s"[UpdateScalaCliVersionInDocs] Bumped versions in ${f.getPath} → $version / scala $scalaVersion"
        )
        IO.write(f, after)
        Some(f)
      } else None
    }

    def walk(f: File): Seq[File] =
      if (f.isDirectory)
        Option(f.listFiles).toSeq.flatten.flatMap(walk)
      else if (
        f.isFile && (f.getName.endsWith(".md") || f.getName.endsWith(".sc"))
      )
        rewrite(f).toSeq
      else Seq.empty

    filesToUpdate.flatMap { f =>
      if (!f.exists()) {
        log.warn(
          s"[UpdateScalaCliVersionInDocs] ${f.getPath} does not exist, skipping"
        )
        Seq.empty
      } else walk(f)
    }
  }
}
