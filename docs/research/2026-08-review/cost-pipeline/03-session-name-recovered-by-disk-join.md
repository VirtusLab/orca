# The manifest writer re-parses progress logs per event to recover a session name the flow had in hand

**Aspect**: complexity  **Severity**: medium

## Problem

On every `SessionCommitted`, `RunManifestWriterState.durableSessionName`
(`runner/src/main/scala/orca/runner/manifest/RunManifestWriter.scala:291-311`) lists
`.orca/progress-*.json`, decodes each file with the full `ProgressLog` codec, and scans for a
`SessionRecord` whose `id` matches the event's `clientId`:

```scala
private def durableSessionName(clientId: String): Option[String] =
  progressLogFiles.iterator
    .flatMap: path =>
      try readFromString[ProgressLog](os.read(path))(using progressLogCodec).sessions
          .find(_.id == clientId).map(_.name)
      catch case NonFatal(_) => None
    .nextOption()
```

This is action-at-a-distance: the `agent.session(name, seed)` call that minted the session
(`flow/src/main/scala/orca/Session.scala`) knows `name`; the information is discarded at the
emission edge (`TurnAccounting.sessionCommitted`, `tools/src/main/scala/orca/agents/TurnAccounting.scala:60-69`
sends only harness/clientId/wireId/agent/role) and reconstructed downstream by joining against
another subsystem's on-disk files. Consequences:

- The manifest writer depends on `ProgressLog`'s codec and directory layout; a progress-log schema
  change silently changes manifest behavior in a file that never mentions sessions-by-name.
- Whether `kind` comes out `"durable"` depends on write ordering between two subsystems (the
  progress-log upsert must land before the event is processed).
- The swallowed catch turns any progress-log hiccup into a silently reclassified `"oneShot"` row.
- `SessionKind.Interactive` (`RunManifestWriter.scala:426-435`) stays unreachable: its scaladoc
  concedes the event "carries nothing that tells them apart" — the event is starved of attribution
  only disk archaeology partially recovers.

## Proposed solution

Carry the name on the event.

1. Add `sessionName: Option[String]` to `OrcaEvent.SessionCommitted`
   (`tools/src/main/scala/orca/events/OrcaEvent.scala:115-121`). No default (repo rule).
2. Thread it to `TurnAccounting`: add a `sessionName: Option[String]` constructor parameter
   (`TurnAccounting.scala:16`), passed through the `private[orca] runWithSession` overloads
   (`tools/src/main/scala/orca/agents/AgentCall.scala:40,62,79,127,140`,
   `BaseAgent.scala:93,148`). `FlowSession.run` (`flow/src/main/scala/orca/Session.scala:86,132`)
   passes `Some(name)`; `Chat` and the one-shot `run`/`resultAs` paths pass `None`. No parameter
   defaults — every call site states it.
3. In `RunManifestWriterState.upsertSession`, read the name off the event; delete
   `durableSessionName`, `progressLogFiles`, and the `progressLogCodec` field
   (`RunManifestWriter.scala:291-311`), removing the writer's `ProgressLog` dependency entirely.
   `SessionKind.of` is unchanged (still keyed on the name's presence).

Tests: update `RunManifestWriterTest` "kind: durable when clientId joins a SessionRecord" to drive
the name via the event instead of a seeded `ProgressStore` (the test loses its store setup —
~20 lines); update `ManifestRoundTripTest` the same way; update every `SessionCommitted`
construction in tests for the new field. Add a `FlowSessionTest`/`SessionTest` assertion that a
durable `session(name, seed).run` emits `SessionCommitted` with `sessionName = Some(name)`.

Must NOT change: the manifest wire format (`ManifestSession.sessionName` keeps its name and JSON
key, so older manifests still decode), the `(harness, wireId-or-clientId)` dedup key, and the
`SessionCommitted` dedup contract in `OrcaEvent`'s scaladoc. The reserved, currently-unreachable
`SessionKind.Interactive` should be resolved in the same change: either complete it (the enriched
event can carry the interactive/autonomous distinction the current one can't) or delete the case —
re-adding it later is trivially additive.

## Verification

**Verdict: CONFIRMED-REVISED** (approach unchanged — carry the name on the event; missed fallout added).

Checked RunManifestWriter.scala:291-301 (`durableSessionName` — minor drift from cited 291-311; quote exact), called per `SessionCommitted` via `upsertSession`:261; the swallowed `NonFatal` → `"oneShot"` reclassification; `SessionKind` at :426-439; TurnAccounting.sessionCommitted :60-69 sends exactly harness/clientId/wireId/agent/role; the `runWithSession` overloads at AgentCall.scala:40/62/79/127/140, BaseAgent.scala:93 (+ TurnAccounting construction at BaseAgent.scala:148, AgentCall.scala:329), Session.scala:86/132.

Solution revision — additional required steps:
- `FlowSession` does not currently hold the name (flow/src/main/scala/orca/Session.scala:55-61 — only `agent` and `id`): add a `private[orca]` name field to `FlowSession` and `FlowSessionCall`, set at the `agent.session(name, seed)` mint site, and pass `Some(name)` from both `run` methods.
- Two production pattern matches missed by the fallout list (compile errors, trivial): `runner/src/main/scala/orca/runner/terminal/TerminalEventListener.scala:86` (wildcard match — one more `_`) and `runner/src/main/scala/orca/runner/LoggingListener.scala:48` (destructures all five fields).
- Stale prose to fix in the same change: the `guarded("session upsert")` comment at RunManifestWriter.scala:176-180 (justified by the disk read being removed), and `ManifestSession`'s scaladoc (RunManifest.scala:11-15), which cites `durableSessionName` and the "carries nothing that tells them apart" rationale.
- Resolve `SessionKind.Interactive` by DELETING it (the simpler offered option; re-adding is additive) — also delete `RunManifest.KindInteractive` (RunManifest.scala:72).
- `ManifestRoundTripTest` currently seeds a real `ProgressStore` (shell/src/test/scala/orca/shell/sessions/ManifestRoundTripTest.scala:28) — drive the name via the event instead, as for RunManifestWriterTest:169.

Ordering: implement before cost-pipeline 05 (05's `ManifestSessionKind` must then omit `Interactive`); same-file overlap with 04 on OrcaEvent.scala/TurnAccounting.scala — either order, but sequentially, never in parallel worktrees.
