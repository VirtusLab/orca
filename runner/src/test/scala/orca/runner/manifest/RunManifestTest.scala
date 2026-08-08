package orca.runner.manifest

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import orca.events.Usage

class RunManifestTest extends munit.FunSuite:

  // Guards the invariant ManifestUsage's scaladoc states: it mirrors every one
  // of Usage's token axes. Without this, an axis ADDED to Usage leaves
  // `ManifestUsage.of` compiling untouched and the cost log silently
  // under-records spend. Every aggregate is a fold over these lines, so an
  // unrecorded axis is unrecoverable rather than recomputable.
  test("ManifestUsage mirrors every token axis of Usage"):
    assertEquals(
      ManifestUsage.of(Usage.empty).productElementNames.toSet,
      // The two of Usage's fields that take another route: `cost` is not
      // carried at all (see ManifestUsage's scaladoc), and `apiCalls` sits
      // beside the usage on each turn line rather than inside it.
      Usage.empty.productElementNames.toSet - "cost" - "apiCalls"
    )

  /** An outcome only a newer build writes must not sink the file: the shell
    * still has sessions to offer from it.
    */
  test("an unrecognised outcome decodes to Unknown, keeping its spelling"):
    val manifest = readFromString[RunManifest](
      """{"orcaVersion":"0.0.test","workDir":"/work","pid":1,
        |"startedAt":"2026-07-18T10:00:00Z","outcome":"abandoned",
        |"sessions":[]}""".stripMargin
    )(using RunManifest.codec)
    assertEquals(manifest.outcome, ManifestOutcome.Unknown("abandoned"))
