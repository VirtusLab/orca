# The turn-entry gate (checkNotClosed + announceEnforcementShortfall) is repeated at four entry points by convention

**Aspect**: complexity  **Severity**: medium

## Problem

Every turn door must remember two calls — `backend.checkNotClosed()` and
`backend.announceEnforcementShortfall(effective, session, events)` — and today
four sites do, each by hand:

- `tools/src/main/scala/orca/agents/BaseAgent.scala:99-101` (autonomous text)
- `BaseAgent.scala:118-125` (`quietTextTurn`)
- `tools/src/main/scala/orca/agents/AgentCall.scala:208` (structured
  autonomous, deliberately inside `attemptOnce` — the "per attempt, not once
  per call" comment at 205-207, because a corrective re-prompt runs `Resumed`,
  a different guarantee on codex)
- `AgentCall.scala:286` (structured interactive)

`announceEnforcementShortfall` is `final` on `AgentBackend`
(`tools/src/main/scala/orca/backend/AgentBackend.scala:141-145`) but nothing
makes callers invoke it. A fifth entry point that forgets gets no notice and no
error — and since the notice is the runtime's only consumer of `Enforcement`
(per `EnforcementNotice`'s own scaladoc), it silently not firing is
indistinguishable from "nothing to report". Understanding when a notice fires
currently means visiting all four sites.

## Proposed solution

Template method on `AgentBackend` (`tools/src/main/scala/orca/backend/AgentBackend.scala`)
so the announce cannot be forgotten, while the doors keep their fail-fast
close check:

```scala
final def runAutonomous(
    prompt: String,
    session: SessionId[B],
    config: AgentConfig,
    events: OrcaListener = OrcaListener.noop,
    outputSchema: Option[String] = None
): AgentResult[B] =
  checkNotClosed()
  announceEnforcementShortfall(config, session, events)
  doRunAutonomous(prompt, session, config, events, outputSchema)

protected def doRunAutonomous(
    prompt: String, session: SessionId[B], config: AgentConfig,
    events: OrcaListener, outputSchema: Option[String]
): AgentResult[B]
```

`runInteractive` currently takes no listener, so the template needs one:
append `events: OrcaListener = OrcaListener.noop` as the LAST parameter
(existing positional call sites in backend tests then compile unchanged),
make it final with the same gate, and delegate to a `protected def
doRunInteractive` with the CURRENT parameter list (the hook does not need the
listener). `AgentCall.runInteractiveOnce` (`AgentCall.scala:297`) passes its
`events` field explicitly.

Rename each backend's implementations to the `do*` hooks (claude, codex,
gemini, opencode, pi, plus every test double implementing
`runAutonomous`/`runInteractive` — coordinate with finding 09's stub pass to
touch each file once).

At the four call sites, delete only the `announceEnforcementShortfall` lines
(`BaseAgent.scala:101`, `BaseAgent.scala:125`, `AgentCall.scala:208`,
`AgentCall.scala:286`). KEEP the `checkNotClosed()` calls at the doors
(`BaseAgent.scala:99`, `BaseAgent.scala:118`, `AgentCall.scala:136`,
`AgentCall.scala:145`, and `resultAs` at `BaseAgent.scala:133`): they run
before any event is emitted, and the guard exists precisely so a leaked
handle can't emit `UserPrompt` to a closed run's dispatcher — the template's
own `checkNotClosed()` is the backstop for a future door that forgets, not a
replacement.

Behavior notes verified against current code, which the implementer must
preserve:

- Per-attempt announcement in `AgentCall.attemptOnce` stays automatic: each
  retry attempt calls `runAutonomous` (the comment at `AgentCall.scala:205-207`
  moves to `AgentBackend`'s scaladoc or is deleted as now-structural).
- `quietTextTurn` now announces via the quiet listener instead of the
  unfiltered `events` — equivalent, because the quiet filter
  (`BaseAgent.scala:120-123`) drops only `AssistantMessage`/`ToolUse` and
  passes `Step` through.
- The notice now fires after the `UserPrompt` event rather than before it —
  an event-ordering change with no consumer that depends on the old order
  (`BaseAgentTest`'s StepRecorder collects Steps only).
- `EnforcementNotice.announceShortfall` resolves dispatch itself from
  `sessions.dispatchFor(session)` (`EnforcementNotice.scala:53`) — unchanged.

Tests: `BaseAgentTest`'s existing notice tests must pass unchanged; add one
test asserting a direct `runAutonomous` call on a stub backend fires the
notice (pinning the template, not the callers). Backend-module tests that
call `runAutonomous` directly on a REAL backend with a recording listener may
newly see the notice `Step` for weak-cell tiers (gemini's read-only turns) —
adjust only such sequence assertions, nothing else.

Must NOT change: the once-per-distinct-sentence dedup, the `Step`+WARN dual
channel, or `checkNotClosed`'s message.

## Verification

**Verdict: CONFIRMED-REVISED.**

Checked all four sites (BaseAgent.scala:99-101, 118-125; AgentCall.scala:208 with the per-attempt comment at 205-207, and 286), AgentBackend.scala:141-145 (final announce) and 184-191 (`checkNotClosed` and its documented purpose), the quiet filter at BaseAgent:120-123 (drops only `AssistantMessage`/`ToolUse` — Step passes), EnforcementNotice.scala:53, and all production callers of `backend.runAutonomous`/`runInteractive` (exactly the four sites). The problem is real.

Revision rationale — two defects fixed in the original solution: (1) `runInteractive` has no `OrcaListener` parameter (AgentBackend.scala:83-89), so the original template literally could not announce; the revision adds a trailing defaulted `events` parameter. (2) Deleting `checkNotClosed` from the doors would weaken the guard: `OrcaEvent.UserPrompt` is emitted before `backend.runAutonomous` is reached (BaseAgent.scala:102, AgentCall.scala:181/214), so moving the close check inside the backend would let a leaked handle emit to a closed run's dispatcher first — the exact thing the guard's scaladoc says it prevents. The ## Proposed solution above is the revised version.

Ordering: touches all five `*Backend.scala` (run-method rename) — different members than 01's forwarder change and 07's `openConversation` rewrite, but sequence 06 after 07 (07 rewrites `openConversation`, called from claude's renamed hook) and pair with 09's stub pass.
