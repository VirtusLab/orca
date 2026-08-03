package orca.runner.manifest

import orca.events.Usage

class RunManifestTest extends munit.FunSuite:

  // Guards the invariant ManifestUsage's scaladoc states: it mirrors every one
  // of Usage's token axes. Without this, an axis ADDED to Usage leaves
  // `ManifestUsage.of` compiling untouched and the manifest silently
  // under-records spend — which is exactly how the cache-write axis went
  // missing. Only the rename that came with it turned that into a compile
  // error; a plain addition would have shipped.
  test("ManifestUsage mirrors every token axis of Usage"):
    assertEquals(
      ManifestUsage.empty.productElementNames.toSet,
      // Usage's two non-token fields, deliberately not persisted here: `cost`
      // at all (see ManifestUsage's scaladoc), and `apiCalls` per turn instead
      // (ManifestTurn) — a subtotal's summed count would silently under-report
      // whenever a backend that can't count contributed to it.
      Usage.empty.productElementNames.toSet - "cost" - "apiCalls"
    )
