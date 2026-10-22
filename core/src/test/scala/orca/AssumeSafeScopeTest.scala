package orca

import orca.testkit.RepoRoot

/** `@assumeSafe` exempts code from safe-mode checking, so it stays in a fixed
  * set of `core` files, reviewed as the trusted base.
  */
class AssumeSafeScopeTest extends munit.FunSuite:

  // The annotation, possibly after others on its line, or its import; not a
  // mention in a comment.
  private val use =
    raw"(?m)^\s*(@[\w.]+(\([^)\n]*\))?\s+)*@(scala\.)?(caps\.)?assumeSafe\b|\bimport\s+(scala\.)?caps\.(assumeSafe\b|\{[^}]*\bassumeSafe\b)".r

  /** Capabilities and pure facades: the only files that may use it. */
  private val allowed: Set[String] = Set(
    "core/src/main/scala/orca/concurrency/concurrency.scala",
    "core/src/main/scala/orca/concurrency/Ref.scala",
    "core/src/main/scala/orca/effect/FileSystem.scala",
    "core/src/main/scala/orca/effect/Network.scala",
    "core/src/main/scala/orca/effect/Processes.scala",
    "core/src/main/scala/orca/json/JsonCodec.scala",
    "core/src/main/scala/orca/json/JsonData.scala",
    "core/src/main/scala/orca/json/JsonSchemas.scala",
    "core/src/main/scala/orca/mcp/mcp.scala",
    "core/src/main/scala/orca/safe/Logger.scala",
    "core/src/main/scala/orca/safe/prelude.scala"
  )

  /** Every module's main sources, plus the flow scripts and examples. */
  private def sourceRoots(root: os.Path): List[os.Path] =
    os.list(root)
      .map(_ / "src" / "main")
      .filter(os.isDir)
      .toList ++ List(root / "flows", root / "examples")

  test("the pattern matches every spelling of the annotation and its import"):
    val spellings = List(
      "@assumeSafe object X",
      "  @caps.assumeSafe final class X",
      "@scala.caps.assumeSafe object X",
      "@deprecated(\"x\") @caps.assumeSafe class X",
      "import caps.assumeSafe",
      "import scala.caps.assumeSafe",
      "import caps.{SharedCapability, assumeSafe as trusted}"
    )
    assertEquals(spellings.filterNot(use.findFirstIn(_).isDefined), Nil)

  test("@assumeSafe appears only in the allowed core files"):
    val root = RepoRoot.dir
    val users = sourceRoots(root)
      .flatMap(os.walk(_))
      .filter(p => os.isFile(p) && (p.ext == "scala" || p.ext == "sc"))
      .filter(p => use.findFirstIn(os.read(p)).isDefined)
      .map(_.relativeTo(root).toString)
      .toSet
    assertEquals(users, allowed)
