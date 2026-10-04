import sbt.*

/** Finds compiled classes whose paths differ only in letter case. Such files
  * overwrite each other on a case-insensitive filesystem (macOS default), and
  * the JVM then fails with `NoClassDefFoundError`.
  */
object ClassNameCase {

  /** Each collision as its colliding paths (relative to their class
    * directory), sorted and joined with " vs ".
    */
  def collisions(classDirs: Seq[File]): Seq[String] = {
    val names = classDirs.flatMap { dir =>
      (dir ** "*.class").get.flatMap(IO.relativize(dir, _))
    }.distinct
    names
      .groupBy(_.toLowerCase)
      .values
      .filter(_.size > 1)
      .map(_.sorted.mkString(" vs "))
      .toSeq
      .sorted
  }
}
