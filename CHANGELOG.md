# Changelog

Notable changes per release, with breaking changes called out. Orca is 0.x, so
breaking changes ship in minor releases; this file is where they are announced.

## 0.1.4 (unreleased)

### Breaking

- **`orca.events.Usage` gained a sixth field, `cacheWriteInputTokens`.** Cache
  creation is now tracked separately from cache reads: `cachedInputTokens`
  means cache *reads* only, and the two are disjoint sub-portions of
  `inputTokens`. Construction is unaffected (the field is last and defaults to
  `0`), but the extractor's arity changed from 5 to 6 — a pattern match like
  `case Usage(in, out, cost) =>` no longer compiles. Match on the fields you
  need by name, or add the two trailing wildcards.
- **`orca.events.ModelPricing` gained a fourth field,
  `cacheWriteUsdPerMillion`,** with no default. A custom pricing table must now
  state a cache-write rate per model; three-argument constructions no longer
  compile. Anthropic bills a write at 1.25× base input on the five-minute cache
  TTL and 2× on the one-hour one; OpenAI's GPT-5.6 family charges 1.25×, and
  earlier OpenAI models charge nothing extra.

### Changed

- The cost summary reports cache reads and cache writes separately
  (`50K in (40K cache read, 5K cache write)`), since a write bills above base
  input and a read far below it.
- The shipped price list was re-checked against provider pricing pages on
  2026-08-02, correcting stale OpenAI and Gemini rows and picking up Claude
  Sonnet 5's introductory pricing (in effect through 2026-08-31).
- The pi adapter now counts a turn's cache reads and writes towards its input
  total. pi reports only the fresh prompt in `input`, so cached turns
  previously under-reported prompt size and were billed as if no fresh input
  had been sent.
