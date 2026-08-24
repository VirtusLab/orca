# `ManifestUsage` re-introduces on disk the inclusive-input convention `Usage.inclusiveInput` exists to eliminate

**Aspect**: complexity  **Severity**: low

## Problem

Commit e6926033 normalized every wire to one convention — three disjoint input axes — at the input
boundary (`tools/src/main/scala/orca/events/Usage.scala:24-26`: an inclusive wire "goes through
`Usage.inclusiveInput`, which splits it"). The persistence boundary flips back
(`runner/src/main/scala/orca/runner/manifest/CostLog.scala:24-27`):

```
`inputTokens` is the one name that isn't a field of `Usage`: it is the TOTAL
prompt, `cacheReadInputTokens` and `cacheWriteInputTokens` INCLUDED, so
adding the three double counts.
```

Both conventions therefore live permanently in the system, and the guard against summing wrong is a
scaladoc paragraph plus the `RunManifestTest` field-mirror pin — not the field names. Any future
consumer of the cost log must rediscover that `inputTokens + cacheRead + cacheWrite` double-counts:
the exact mistake `inclusiveInput` was built to absorb at the other end.

## Proposed solution

Persist `Usage`'s own disjoint axis instead of the derived total. In
`runner/src/main/scala/orca/runner/manifest/CostLog.scala:33-48`:

```scala
private[orca] case class ManifestUsage(
    freshInputTokens: Long,
    cacheReadInputTokens: Long,
    cacheWriteInputTokens: Long,
    outputTokens: Long,
    reasoningOutputTokens: Long
)
```

`ManifestUsage.of` becomes a field-for-field copy; the total is derivable on read. This is a wire
rename for the cost log, which is explicitly free: AGENTS.md's versioning section says "`CostRecord`
is not covered [by the manifest compat rule]: nothing reads a cost log, and no fixture pins it."

Fallout:
- `RunManifestTest`'s mirror test simplifies to exact field-set equality minus `cost`/`apiCalls`
  (`- "freshInputTokens" + "inputTokens"` disappears), and the `ManifestUsage` scaladoc loses its
  two warning paragraphs — the names now carry the invariant.
- `CostLogTest` "a turn carries every usage axis" updates its expected `ManifestUsage` (the fixture
  currently asserts `inputTokens = 120_000` for a usage built with cache parts; recompute with the
  disjoint fresh figure).

Must NOT change: `RunManifest`/`ManifestSession` (separate file, compat-covered), `Usage` itself,
and the rule that `ManifestUsage` carries no money.

## Verification

**Verdict: CONFIRMED.**

Checked Usage.scala:24-26 (inclusive wires split at `inclusiveInput`), CostLog.scala:24-27 (scaladoc quoted verbatim), `ManifestUsage` at :33-48, RunManifestTest.scala:12-20 (the mirror pin — the proposed simplification to `- "cost" - "apiCalls"` is exactly right), CostLogTest.scala:144-174 (fixture built via the inclusive `Usages.usage` helper, so the recomputed disjoint fresh figure is 5_000). AGENTS.md's CostRecord carve-out is quoted accurately — the wire rename is sanctioned. `ManifestUsage` is referenced only by `RunManifestWriter`, `RunManifestTest`, and `CostLogTest`; no callers missed. Ordering: touches CostLog.scala, which 06 also retypes — sequence with 06.
