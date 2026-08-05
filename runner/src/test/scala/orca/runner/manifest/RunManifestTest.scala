package orca.runner.manifest

import orca.events.Usage

class RunManifestTest extends munit.FunSuite:

  // Guards the invariant ManifestUsage's scaladoc states: it mirrors every one
  // of Usage's token axes. Without this, an axis ADDED to Usage leaves
  // `ManifestUsage.of` compiling untouched and the cost log silently
  // under-records spend — which is exactly how the cache-write axis went
  // missing. Only the rename that came with it turned that into a compile
  // error; a plain addition would have shipped. Every aggregate is now a fold
  // over these lines, so an unrecorded axis is unrecoverable rather than
  // recomputable.
  test("ManifestUsage mirrors every token axis of Usage"):
    assertEquals(
      ManifestUsage.of(Usage.empty).productElementNames.toSet,
      // Usage's two non-token fields, deliberately not carried here: `cost` at
      // all (see ManifestUsage's scaladoc), and `apiCalls` beside the usage on
      // each turn line rather than inside it.
      Usage.empty.productElementNames.toSet - "cost" - "apiCalls"
    )
