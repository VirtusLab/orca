# `ManifestReaderTest` fixtures carry dead `cost`/`turns` JSON blocks

**Aspect**: conciseness  **Severity**: low

## Problem

`shell/src/test/scala/orca/shell/sessions/ManifestReaderTest.scala:29-41` — the shared
`writeManifest` fixture embeds a 13-line `"cost"` object and a `"turns"` array that are not fields
of `RunManifest` (`runner/src/main/scala/orca/runner/manifest/RunManifest.scala:52-61` — the
manifest "carries no cost or turn data"). The same block is repeated in the `no-sessions.json`
fixture (:132-144), directly under a comment claiming "`sessions` is the ONLY field omitted" — which
is misleading, since the fixture also contains fields the schema never had. These blocks decode as
skipped unknown fields, so every test passes for reasons unrelated to them; unknown-field tolerance
is already pinned separately and deliberately by the sanctioned verbatim-older-manifest test
(:76-93), which must stay.

## Proposed solution

Delete the `"cost"` and `"turns"` blocks from `writeManifest` and from the `no-sessions.json`
fixture (~26 lines). The `no-sessions` test's comment then becomes true as written. If pinning
"a NEWER build's extra field is skipped" is wanted (distinct from the older-build test), add one
minimal fixture with a single unknown scalar field — not a stale copy of a schema this build never
had.

Must NOT change: the verbatim older-build manifest test (:71-93) — AGENTS.md sanctions it explicitly —
and every test's assertions.

## Verification

**Verdict: CONFIRMED.**

Checked ManifestReaderTest.scala:29-41 (shared `writeManifest` embeds the 13-line `"cost"` object + `"turns"` array), :122-144 (the `no-sessions.json` fixture repeats the block under the ":sessions is the ONLY field omitted" comment — false as written), :76-93 (the sanctioned verbatim older-build test, untouched). `RunManifest` has no `cost`/`turns` fields (RunManifest.scala:52-61) and jsoniter skips unknown fields (RunManifest.scala:78-85), so deletion changes no behavior. Solution precise; nothing missed. Ordering: 06 also edits ManifestReaderTest assertions — sequence.
