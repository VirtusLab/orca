package orca.json

import orca.testkit.RepoRoot

import scala.util.matching.Regex

/** Every type whose schema [[SchemaDerivation]] builds in main sources has a
  * `parity[...]` entry in some `SchemaParitySuite`, so the parity tests grow
  * with the codebase.
  *
  * Types are matched by simple name, as found in the source text. A
  * parameterless enum is exempt: it takes the string-enum path, whose schema
  * tapir's `Schema.derivedEnumeration` builds directly.
  */
class SchemaParityCoverageTest extends munit.FunSuite:

  /** Sites pinned elsewhere, and where. */
  private val exempt: Map[String, String] = Map(
    // Its `Segment` is private.
    "Stage" -> "StagePathTest pins the rendered schema"
  )

  private val declaration: Regex =
    raw"\b(case class|class|enum|trait|object)\s+(\w+)".r
  private val derivesClause: Regex = raw"\bderives\b[^\n]*\bJsonData\b".r
  private val givenInstance: Regex =
    raw"\bgiven\s+(?:\w+\s*:\s*)?(JsonData|Schema)\[(\w+(?:\.\w+)*)\]\s*=".r
  private val derivedCall: Regex = raw"\bJsonData\.derived\[(\w+(?:\.\w+)*)\]".r
  private val parityEntry: Regex = raw"\bparity\[(\w+(?:\.\w+)*)\]".r
  private val comment: Regex = raw"(?s)/\*.*?\*/|(?m)(^|\s)//[^\n]*".r

  private def scalaFiles(dirs: List[os.Path]): List[os.Path] =
    dirs
      .filter(os.isDir)
      .flatMap(os.walk(_))
      .filter(p => os.isFile(p) && p.ext == "scala")

  private def moduleDirs(sub: String): List[os.Path] =
    os.list(RepoRoot.dir).map(_ / "src" / sub).toList

  private def simpleName(name: String): String = name.split('.').last

  /** The derivation sites in `src`, by type name. */
  private def sites(src: String): Set[String] =
    val derived = derivesClause
      .findAllMatchIn(src)
      .flatMap(m => owner(src, m.start))
      .toSet
    val givens = givenInstance
      .findAllMatchIn(src)
      .filter(m => derivesSchema(m.group(1), statement(src, m.end)))
      .map(_.group(2))
      .toSet
    (derived ++ givens ++ derivedCall.findAllMatchIn(src).map(_.group(1)))
      .map(simpleName)

  /** The type a `derives` clause at `at` belongs to, unless it is exempt. */
  private def owner(src: String, at: Int): Option[String] =
    declaration
      .findAllMatchIn(src.substring(0, at))
      .toList
      .lastOption
      .filterNot(d => d.group(1) == "enum" && parameterless(src, d.start))
      .map(_.group(2))

  /** Whether no case of the enum declared at `at` has fields. */
  private def parameterless(src: String, at: Int): Boolean =
    val lineStart = src.lastIndexOf('\n', at) + 1
    val indent = at - lineStart
    src
      .substring(lineStart)
      .linesIterator
      .drop(1)
      .takeWhile(l => l.isBlank || l.segmentLength(_ == ' ') > indent)
      .filter(_.trim.startsWith("case "))
      .forall(!_.contains('('))

  /** The text from `at` to the next blank line. */
  private def statement(src: String, at: Int): String =
    val end = src.indexOf("\n\n", at)
    src.substring(at, if end < 0 then src.length else end)

  // A `Schema` given counts only when derived here; a `JsonData` one unless
  // it travels as a string.
  private def derivesSchema(kind: String, rhs: String): Boolean =
    if kind == "Schema" then
      rhs.contains("SchemaDerivation.derived") ||
      rhs.contains("JsonData.derivedSchema")
    else !rhs.contains("JsonData.fromString")

  // The derivation's own generic givens; SchemaDerivationTest covers them.
  private def isDerivation(p: os.Path): Boolean =
    p.startsWith(
      RepoRoot.dir / "core" / "src" / "main" / "scala" / "orca" / "json"
    )

  private lazy val derivationSites: Set[String] =
    scalaFiles(moduleDirs("main"))
      .filterNot(isDerivation)
      .flatMap(p => sites(comment.replaceAllIn(os.read(p), "")))
      .toSet

  test("every derivation site has a parity entry"):
    val covered = scalaFiles(moduleDirs("test"))
      .flatMap(p => parityEntry.findAllMatchIn(os.read(p)).map(_.group(1)))
      .map(simpleName)
      .toSet
    assertEquals(derivationSites -- covered -- exempt.keySet, Set.empty[String])

  test("every exemption names a derivation site"):
    assertEquals(exempt.keySet -- derivationSites, Set.empty[String])
