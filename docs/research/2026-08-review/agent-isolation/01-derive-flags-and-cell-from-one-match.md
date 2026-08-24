# Per-backend argv building and enforcement classification are parallel matches whose agreement is convention-only

**Aspect**: complexity  **Severity**: high

## Problem

Each backend answers "what flags does this tier emit" and "what guarantee do those
flags achieve" (`enforcementCell`) in **separate exhaustive matches over the same
axes**, and nothing checks they describe the same thing. This is the core
remaining action-at-a-distance the owner is complaining about.

Worst case is codex: `codex/src/main/scala/orca/tools/codex/CodexArgs.scala`
contains **six** independent matches over `ToolSet`/`AutoApprove` that must all
agree — `sandboxArgs` (157-165), `resumeSandboxModeArgs` (99-104),
`resumeSandboxArgs` (111-118), `networkConfigArgs` (172-176), `freshCell`
(196-222), `resumedCell` (224-244) — held together only by cross-referencing
scaladoc. The same two-parallel-matches shape exists in every backend:

- `claude/src/main/scala/orca/tools/claude/ClaudeArgs.scala`: `permissionArgs`
  (126-144) vs `enforcementCell` (173-205)
- `gemini/src/main/scala/orca/tools/gemini/GeminiArgs.scala`: 74-87 vs 94-118
- `opencode/src/main/scala/orca/tools/opencode/OpencodeArgs.scala`: 85-104 vs 109-128
- `pi/src/main/scala/orca/tools/pi/PiArgs.scala`: 52-68 vs 76-100

Example drift that compiles and passes all tests: edit `CodexArgs.sandboxArgs`'s
`NetworkOnly` arm from `--full-auto` to `--sandbox read-only` — `freshCell`'s
`PromptOnly` cell and its rationale ("network needs the `workspace-write`
sandbox, which also permits writes") now describe an argv that no longer exists,
and `EnforcementTableTest` stays green because it only compares levels against a
hand-written copy of levels.

Two adjacent pieces of pure boilerplate ride on the same structure:

- Four of the five `enforcementCell`s open with an identical
  `dispatch match / case Fresh | Resumed =>` scaffold plus a "Same either way:
  [[X]] emits Y on resume too" comment (claude 177-179, gemini 98-100,
  opencode 113-115, pi 80-82); only codex genuinely branches on dispatch.
- All five `*Backend.scala` files carry an identical 6-line forwarding override
  (`ClaudeBackend.scala:127-132`, `CodexBackend.scala:75-80`,
  `GeminiBackend.scala:70-75`, `OpencodeBackend.scala:130-135`,
  `PiBackend.scala:80-85`):
  ```scala
  override def enforcementCell(
      tools: ToolSet, autoApprove: AutoApprove, dispatch: TurnDispatch
  ): EnforcementCell = XxxArgs.enforcementCell(tools, autoApprove, dispatch)
  ```

## Proposed solution

Per backend, **one match that produces both the argv fragments and the cell**, so
they cannot drift. Keep it backend-local — the argv shapes genuinely differ, so
no shared supertype is needed; only the pattern is shared.

Codex (the payoff case), in `CodexArgs.scala`:

```scala
/** One cell of the isolation matrix: the flags codex emits for it and the
  * guarantee they achieve — one match, so they cannot drift. */
private[codex] case class SandboxWiring(
    preSubcommand: Seq[String],   // global `-c` overrides (before `exec`)
    postSubcommand: Seq[String],  // --sandbox / --full-auto / bypass flags
    cell: EnforcementCell
)

private[codex] def sandboxWiring(
    tools: ToolSet, autoApprove: AutoApprove, dispatch: TurnDispatch
): SandboxWiring
```

- `exec` splices `sandboxWiring(..., Fresh).preSubcommand` /
  `.postSubcommand` where it currently calls `networkConfigArgs` /
  `sandboxArgs`; `execResume` splices the `Resumed` wiring where it calls
  `resumeSandboxModeArgs` / `networkConfigArgs` / `resumeSandboxArgs`.
- `enforcementCell` becomes `sandboxWiring(tools, autoApprove, dispatch).cell`.
- The six matches collapse to one (with fresh/resumed arms inside), and the
  cross-referencing comments that currently hold them together are deleted —
  each arm's flags sit next to the cell that classifies them, and the probe
  citations stay on the arm they justify.

Gemini/pi/opencode get the two-field version (argv fragment / body-flags map +
`cell`); their `Fresh | Resumed` scaffold disappears because the single wiring
match is dispatch-invariant by construction (write it without a `dispatch`
parameter and let `enforcementCell` ignore its `dispatch` argument, with a
one-line comment saying why that is sound for that backend — the flags ride on
every spawn/message). Claude's version takes the `networkTools`/`mcpTools`
grant parameters that `permissionArgs` needs (the cell side ignores them);
that one is about break-even in lines but becomes single-point-of-change.

Replace the five 6-line `*Backend` forwarders with `export
XxxArgs.enforcementCell` (one line each); if a backend exposes the wiring
function instead, keep a one-line `override def` forwarding to `.cell`.

Tests: no behavior changes, so the existing `*ArgsTest` argv assertions and
`EnforcementTableTest` must pass unchanged — that is the review gate. Do NOT
change `EnforcementTableTest`'s hand-written expected copy, the rendered
AGENTS.md block, any flag actually emitted, or any cell level/rationale text.

Net lines across the five backends go down (~30 from the scaffold and
forwarders alone), and a reader of one backend sees its whole isolation story
in one place.

## Verification

**Verdict: CONFIRMED.**

Checked all six codex matches at the cited lines, the parallel pairs in ClaudeArgs/GeminiArgs/OpencodeArgs/PiArgs, the five `*Backend` forwarders, the four `Fresh | Resumed` scaffolds, and EnforcementTableTest (confirms it compares levels only against a hand-written copy — the drift example genuinely stays green). No line drift. All six codex matches are private with no test callers (tests go through `exec`/`execResume`/`streamJson`), so the restructure is safe.

Implementation notes verified against current code: (a) In `execResume`, `resumeSandboxModeArgs` precedes `networkConfigArgs` — the Resumed wiring's `preSubcommand` must preserve that order. (b) opencode's wiring covers only the write gate; the `question`-by-mode gating and the map merge stay in `toolFlags` untouched. (c) pi's wiring takes the `includeAskUser` parameter the same way claude's takes `networkTools`/`mcpTools` — the cell side ignores it. (d) The `export XxxArgs.enforcementCell` one-liner relies on an export forwarder implementing the inherited abstract member; if the pinned compiler rejects that, keep a one-line `override def` forwarder — the win is the merged match, not the forwarder. (e) `EnforcementTableTest`'s hand-written expected copy and the rendered AGENTS.md block need no regeneration — levels and rationale strings are unchanged by construction.

Ordering: implement AFTER 02+03 (both edit OpencodeArgs' writeGate/cell lines this finding restructures) and fold finding 11's codex flag change plus finding 08's codex probe-consolidation into the same `SandboxWiring` pass. Claude's wiring interacts with 07 (which reshapes the grant assembly feeding `permissionArgs`) — sequence 01 before 07, or merge the claude portions.
