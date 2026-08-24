# Enforcement prose: one fact, several homes; comments restating code

**Aspect**: conciseness  **Severity**: low

## Problem

The per-cell rationale strings are the designated single home for enforcement
reasoning (`EnforcementCell`'s scaladoc,
`tools/src/main/scala/orca/agents/Enforcement.scala:41-46`), yet the same facts
are narrated in several other places, and a few comments restate the code below
them. ~70 lines of prose whose content already lives elsewhere:

1. `Enforcement.scala:8-16` — the `Hard`/`SandboxApprox` bullets embed claude's
   and codex's specific cases ("claude's `--allowedTools` adds to the default
   permission mode…", "claude's additive allowlist can (defaults ∪ the list),
   codex's swap to a whole-workspace sandbox cannot") — the literal content of
   `ClaudeArgs.scala:204` and `CodexArgs.scala:221`. The same scaladoc says
   (line 25) rationale lives in `*Args.enforcementCell`, then carries two of
   those rationales anyway.
2. `Enforcement.scala:52-54` (`TurnDispatch` doc) — restates codex's
   `Full`+`Only` resume case, already `CodexArgs.scala:183-186` (whose own
   "and now in one cell only" is history narration).
3. `tools/src/main/scala/orca/backend/AgentBackend.scala:120-124` — narrates
   what test doubles do (`testkit/StubEnforcement.scala:5-11` already documents
   that where it lives); 125-129's `@see` repeats `Enforcement.scala:23-25`'s
   pointer to `EnforcementTableTest`.
4. `AGENTS.md:181-189` — the paragraph after the rendered table re-describes
   `EnforcementNotice`'s dedup behavior, duplicating
   `EnforcementNotice.scala:20-31`.
5. `codex/src/main/scala/orca/tools/codex/CodexArgs.scala` — the two
   2026-08-07/0.145.0 probes are written up five times: 62-67 (`execResume`
   doc), 88-92 (`resumeSandboxModeArgs` doc), and in three rationale strings
   (204, 238, 243).
6. `claude/src/main/scala/orca/tools/claude/ClaudeArgs.scala:204` — the
   `Full`+`Only` rationale is a five-sentence essay, violating
   `EnforcementCell`'s "a single unwrapped sentence or two" contract; the
   additive-allowlist fact is already in `permissionArgs`' doc (118-120). For a
   `Hard` cell the rationale is never rendered at runtime
   (`EnforcementNotice.consequenceOf(Hard) = None`), so this is a comment
   stored as data.
7. `tools/src/main/scala/orca/backend/SystemPromptComposer.scala:78-81` — the
   `combine` comment restates the four-line match below it; 49-55 narrates
   another file's teardown implementation and a pending change ("the teardown
   change in flight"), which goes stale when that lands; line 40's "$1.44
   across two reviewer turns" is history.
8. `tools/src/main/scala/orca/agents/EnforcementNotice.scala` — ~80 of 136
   lines are commentary; lines 27-32 spend six lines on "keyed by the rendered
   sentence" (two suffice), 11-15 are motivation essay.
9. `gemini/src/main/scala/orca/tools/gemini/GeminiArgs.scala:105` — the
   rationale string ends "Raise this cell when a probe establishes more." — a
   maintainer instruction inside runtime data that `EnforcementNotice` WARNs to
   operators.

## Proposed solution

Single-home rule: level semantics in `Enforcement`'s bullets (one line each, no
backend examples); per-cell reasoning in that backend's rationale string (one
or two sentences plus the dated probe citation); probe narratives once, on the
function the probe justifies. Concretely:

- Trim `Enforcement.scala:8-16` bullets to one line each; drop the codex
  example from `TurnDispatch`'s doc.
- Cut `AgentBackend.enforcementCell`'s doc to ~10 lines: drop the test-double
  narration and the duplicate `@see`.
- Shrink AGENTS.md:181-189 to two sentences pointing at
  `*Args.enforcementCell` and `EnforcementNotice`.
- CodexArgs: keep the full probe account once, on `resumeSandboxModeArgs`;
  `execResume`'s doc references it; rationales keep only
  "(probed 2026-08-07, codex-cli 0.145.0)".
- ClaudeArgs:204 → "`--allowedTools` is a hard but ADDITIVE gate: the approved
  set is claude's default permission mode ∪ the list (probed 2026-08-07,
  claude 2.1.224)"; move the probe details into `permissionArgs`' scaladoc.
- SystemPromptComposer: delete the 78-81 comment (keep at most one line on the
  `selfManagedGit` exception); in 34-55 keep the load-bearing fact (the rule is
  unconditional because a read-only agent can also strand background work) and
  the per-backend turn-boundary note; cut the teardown-in-flight and cost
  narration.
- EnforcementNotice: halve the class doc and `said`/`announceShortfall` docs.
- GeminiArgs: move the "Raise this cell…" sentence to a comment above the cell.

Tests: `EnforcementTableTest` renders levels only, so most edits are free;
`ClaudeArgs`/`GeminiArgs` rationale-string edits may require pasting the
table test's freshly printed block if any rationale is asserted anywhere
(check `*ArgsTest` for substring assertions on rationale text first).

Must NOT change: any `Enforcement` level, the rendered table, the probe dates/
versions themselves, or `EnforcementNotice`'s wording of the user-facing
sentences.

## Verification

**Verdict: CONFIRMED.**

Checked every cited passage verbatim: Enforcement.scala:8-16/:25/:50-54; AgentBackend.scala:118-129; AGENTS.md:181-189; the five codex probe write-ups (CodexArgs.scala:62-67, 88-92, rationales at 204/238/243, all carrying "2026-08-07 / 0.145.0"; ":184-185's "and now in one cell only" is history narration); ClaudeArgs.scala:204 (five-sentence rationale, additive fact duplicated at :118-120; `consequenceOf(Hard) = None` confirmed, so the rationale is never rendered); SystemPromptComposer.scala:39-40/:48-54/:78-81; EnforcementNotice commentary volume; GeminiArgs.scala:105. Grepped all test modules for rationale-substring assertions: none — the edits are free, no table regeneration needed (levels untouched).

Two cautions for the implementer: (a) when trimming Enforcement.scala's bullets, keep the Hard-vs-SandboxApprox boundary rule ("whether orca can name the boundary the agent is held to") — drop only the backend examples, not the rule. (b) The codex trims overlap finding 01's CodexArgs restructure; if 01 lands, do the codex probe-consolidation as part of that change (the probe account then lives on the wiring arm it justifies) rather than as a separate pass.
