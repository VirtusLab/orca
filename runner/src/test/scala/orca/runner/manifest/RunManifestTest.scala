package orca.runner.manifest

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
      // Three of Usage's fields take another route: `freshInputTokens` is
      // persisted through the `inputTokens` total it feeds, `cost` is not
      // carried at all (see ManifestUsage's scaladoc), and `apiCalls` sits
      // beside the usage on each turn line rather than inside it.
      Usage.empty.productElementNames.toSet
        - "freshInputTokens" + "inputTokens" - "cost" - "apiCalls"
    )
