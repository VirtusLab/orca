package orca.shell

class ClassNameCaseCollisionTest extends munit.FunSuite:

  // A case-insensitive filesystem loads either class for both names, which the
  // JVM rejects; Linux CI cannot hit that, so the names are compared instead.
  test("no two orca classes on the classpath differ only in letter case"):
    val names = classpathDirs.flatMap(orcaClassNames).distinct
    assert(names.nonEmpty, "found no orca classes on the test classpath")
    val collisions = names
      .groupBy(_.toLowerCase)
      .values
      .filter(_.sizeIs > 1)
      .map(_.sorted)
      .toList
    assertEquals(collisions, Nil)

  private def classpathDirs: List[os.Path] =
    sys
      .props("java.class.path")
      .split(java.io.File.pathSeparator)
      .toList
      .map(os.Path(_, os.pwd))
      .filter(os.isDir)

  private def orcaClassNames(dir: os.Path): Seq[String] =
    val root = dir / "orca"
    if !os.isDir(root) then Nil
    else
      os.walk(root)
        .filter(_.ext == "class")
        .map(_.relativeTo(dir).toString)
