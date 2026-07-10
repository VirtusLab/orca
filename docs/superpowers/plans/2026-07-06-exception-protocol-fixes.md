# Exception-Protocol Fixes (findings 5.1–5.3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make event emission total (listener throws can no longer alter flow control), collapse retry classification to two documented sites, and replace the `alreadyEmitted` mutable-flag protocol with a context-owned reported-set.

**Architecture:** Three ordered fixes: (1) `EventDispatcher` catches per-listener — which kills the only real feeder of `drainAndCommit`'s laundering branch; (2) delete that branch and document the classifier (`awaitResult`) / policy (`DefaultAgentCall`) pairing, fixing claude's impossible-retry comments; (3) exactly-once error reporting moves off the exception object onto the `FlowContext` as an identity-set, also fixing plain-`RuntimeException` double-reporting through nested stages.

**Tech Stack:** Scala 3 (braceless), Ox, sbt.

## Global Constraints

- Zero compile warnings; braceless Scala 3; explicit return types on public members.
- `sbt --client` loops; `scalafmtAll` before every commit; full gate per task: `sbt --client "scalafmtAll; compile; Test/compile; test"`.
- Commits end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`; never commit `.superpowers/` or `docs/superpowers/`.
- Branch: continue on `session-identity-fixes`.
- User decisions (binding): 5.1 = two-site classification + comments (NOT the Either-through-SPI reshape); 5.2 = identity-set on the context (NOT a wrapper exception type).
- Concurrency preference (standing): the reported-set is genuinely cross-thread (`fail(...)` can run inside `mapParUnordered` forks; the check happens on the stage thread after the fork's failure propagates through the join), so an `AtomicReference[List[Throwable]]` with pure `updateAndGet` is the sanctioned "pure atomic state" shape — document exactly that at the definition. Do NOT add locks/actors for it.
- Task order is load-bearing: Task 1 (5.3) MUST land before Task 2 (5.1) — the deleted branch is only dead once the dispatcher is total.

---

### Task 1: Total `EventDispatcher.emit` (finding 5.3)

**Files:**
- Modify: `flow/src/main/scala/orca/events/EventDispatcher.scala`
- Modify: `tools/src/main/scala/orca/events/OrcaEvent.scala` (the `OrcaListener` scaladoc's "Throwing from onEvent propagates" contract)
- Modify: `flow/src/main/scala/orca/Flow.scala:62-66` (the `resumeFrom` comment justifying emits-outside-try)
- Test: `flow/src/test/scala/orca/EventDispatcherTest.scala`

**Interfaces:** `EventDispatcher` signature unchanged; behavior: a listener throw is caught, logged at WARN, and the remaining listeners still receive the event.

- [ ] **Step 1: Failing test** — in `EventDispatcherTest` (read it first; reuse its recording listeners):

```scala
test("a throwing listener does not stop the others and does not propagate"):
  val received = List.newBuilder[String]
  val bad = new OrcaListener:
    def onEvent(event: OrcaEvent): Unit = throw new RuntimeException("boom")
  val good = new OrcaListener:
    def onEvent(event: OrcaEvent): Unit = received += "good"
  val dispatcher = new EventDispatcher(List(bad, good))
  dispatcher.onEvent(OrcaEvent.Step("x"))   // must NOT throw
  assertEquals(received.result(), List("good"))
```
RED: the current dispatcher propagates `boom`.
- [ ] **Step 2: Implement**:

```scala
class EventDispatcher(listeners: List[OrcaListener]) extends OrcaListener:
  private val log = LoggerFactory.getLogger(classOf[EventDispatcher])

  /** Total: a listener that throws is logged (WARN — visible on the console)
    * and skipped; the remaining listeners still see the event and the emitter
    * is never disturbed. Observation must not alter flow control — stage
    * bookkeeping used to depend on emit-placement relative to try blocks to
    * defend against throwing listeners; making emit total retires that whole
    * class of ordering constraints.
    */
  def onEvent(event: OrcaEvent): Unit =
    listeners.foreach: l =>
      try l.onEvent(event)
      catch
        case NonFatal(e) =>
          log.warn(
            s"listener ${l.getClass.getName} failed on ${event.getClass.getSimpleName}",
            e
          )
```
(keep the composability scaladoc paragraph; drop the "Listener exceptions propagate" rationale.)
- [ ] **Step 3: Contract docs.** `OrcaListener` scaladoc: replace the "Throwing from onEvent propagates" sentence — implementations should still not throw, but a throw is now logged-and-skipped by the dispatcher, never surfaced to the emitting flow. `Flow.scala` `resumeFrom` comment: keep the emits-outside-the-decode-try placement (still the clearest shape) but reword the justification — the decode try is scoped to the decode only; listener throws no longer reach here.
- [ ] **Step 4: Verify** full gate. **Step 5: Commit** — `fix(events): make EventDispatcher total — listener failures are logged, never control flow`.

---

### Task 2: Two-site retry classification (finding 5.1)

**Files:**
- Modify: `tools/src/main/scala/orca/backend/Conversations.scala` (`drainAndCommit` — delete the laundering)
- Modify: `tools/src/main/scala/orca/backend/ForkedConversation.scala` (`awaitResult` scaladoc — name it THE classifier)
- Modify: `tools/src/main/scala/orca/OrcaFlowException.scala` (`AgentTurnFailed` scaladoc cross-references)
- Modify: `tools/src/main/scala/orca/agents/AgentCall.scala` (`runAutonomousWithRetry` — name it THE policy; tighten the parse-retry comments)
- Modify: `claude/src/main/scala/orca/tools/claude/ClaudeBackend.scala` (~:117-119, ~:219 — impossible-retry comments)
- Test: `tools/src/test/scala/orca/backend/ConversationsTest.scala`, `claude/src/test/scala/orca/tools/claude/DefaultAgentCallTest.scala`

**Interfaces:** `drainAndCommit` body becomes drain + commit with NO try/catch:

```scala
  def drainAndCommit[B <: BackendTag](
      backendName: String,
      conv: Conversation[B],
      session: SessionId[B],
      registry: SessionRegistry[B],
      events: OrcaListener = OrcaListener.noop
  ): AgentResult[B] =
    val result = drainAutonomous(conv, events)
    registry.commitSuccess(session, result.wireId)
    result
```

- [ ] **Step 1: Verify deadness, then delete.** Before deleting the `case e: OrcaFlowException => rewrap` branch, confirm its feeders are gone: the reader-side failures all surface via `awaitResult` (already `AgentTurnFailed`); listener throws no longer escape (Task 1). Remaining theoretical feeders are driver `respond(...)` callbacks throwing inside `drainAutonomous`'s event handling — those now propagate VERBATIM instead of being re-labelled "`$backendName` CLI failed: …", which is more honest (note this behavior delta in the commit message). Delete the try/catch; keep the `backendName` param (used by `runAutonomous`'s signature and future diagnostics? — if it becomes UNUSED, remove it from `drainAndCommit` but keep it on `runAutonomous` where the scaladoc references the backend; let the compiler decide and say what you did).
- [ ] **Step 2: Rewrite the contract docs as a named pair.**
  - `ForkedConversation.awaitResult` scaladoc: "THE retryability classifier: every post-spawn failure is (re)thrown as [[AgentTurnFailed]] — by the time the reader runs, the wire session may exist, so a retry against the same id would only cascade ('already in use' / broken pipe). Pre-spawn open failures never reach this method — they throw from `openConversation` as plain [[OrcaFlowException]]s and stay retryable. The retry POLICY lives in `DefaultAgentCall.runAutonomousWithRetry`."
  - `AgentTurnFailed` scaladoc: add the two-site pointer (classifier: `ForkedConversation.awaitResult`; policy: `DefaultAgentCall.runAutonomousWithRetry`).
  - `drainAndCommit` scaladoc: drop the rewrap invariant paragraph; keep commit-after-clean-drain; add "failures propagate verbatim — classification happened in `awaitResult`".
  - `AgentCall.runAutonomousWithRetry`: head comment becomes "THE retry policy — the only place retryability is decided: retry parse failures (corrective re-prompt) and pre-spawn open failures; never [[AgentTurnFailed]] (see `awaitResult`, the classifier)". Keep the Ox `retry` + the sanctioned method-scope `var lastFailure`, but tighten locality: move the `var` + `FailedAttempt` handling comment so the retry condition and the corrective-prompt derivation read together (the comment block should say: the var is written only in the parse-failure catch, read only at the top of the next attempt, single-threaded by `retry`'s sequential re-execution).
- [ ] **Step 3: Fix claude's impossible-retry comments.** `ClaudeBackend.scala` ~:117-119 currently reasons "a retry will still try `--session-id`" about post-spawn crashes — but post-spawn failures are `AgentTurnFailed` and are NEVER auto-retried. Rewrite both comment sites to state the true rationale: commit-after-clean-drain keeps the registry consistent for the NEXT `session(...)` call / resumed run, not for an automatic retry (which doesn't happen for turn failures).
- [ ] **Step 4: Tests.** In `ConversationsTest`: a drain failure from a conversation whose `awaitResult` throws `AgentTurnFailed` propagates VERBATIM through `drainAndCommit` (message unchanged — pins the deleted rewrap). In `DefaultAgentCallTest`: confirm existing coverage pins (a) parse failure → corrective retry, (b) `AgentTurnFailed` → no retry, (c) open failure (backend throws plain `OrcaFlowException` on first call, succeeds on second) → retried; ADD whichever of the three is missing (read the file first — (a) and (b) likely exist).
- [ ] **Step 5: Verify** full gate. **Step 6: Commit** — `refactor(backend): two-site retry classification — delete the dead rewrap; fix impossible-retry comments`.

---

### Task 3: Reported-set replaces `alreadyEmitted` (finding 5.2)

**Files:**
- Modify: `tools/src/main/scala/orca/OrcaFlowException.scala` (delete the var + private ctor; single `class OrcaFlowException(message: String) extends RuntimeException(message)`; class scaladoc updated — reporting state lives on the context now)
- Modify: `flow/src/main/scala/orca/FlowContext.scala` (two new `private[orca]` methods), `flow/src/main/scala/orca/Flow.scala` (`fail`, `runStage` catch), `runner/src/main/scala/orca/runner/DefaultFlowContext.scala` (the state), `runner/src/main/scala/orca/runner/FlowLifecycle.scala` (`run`'s catch)
- Modify: every test `FlowContext`/`FlowControl` implementation (grep `extends FlowContext|extends FlowControl` in test sources — at least `flow/src/test/scala/orca/TestFlowContext.scala`, `runner/.../FlowLifecycleTest`'s StubFlowContext, review-test contexts)
- Test: `flow/src/test/scala/orca/StageRuntimeTest.scala` (or wherever single-emission is pinned — grep `alreadyEmitted` in tests), `runner/.../FlowLifecycleTest.scala`

**Interfaces:**
- `FlowContext` gains:

```scala
  /** Exactly-once error reporting: the runtime marks a throwable here when it
    * publishes an `OrcaEvent.Error` for it, and every enclosing frame (nested
    * stages, the flow boundary) checks before re-reporting. Identity-based
    * (`eq`) — the mark travels with the object, not its type or message, so
    * plain RuntimeExceptions are covered too. `private[orca]`: user code never
    * participates.
    */
  private[orca] def markErrorReported(e: Throwable): Unit
  private[orca] def errorAlreadyReported(e: Throwable): Boolean
```

- `DefaultFlowContext` implements with the sanctioned pure-atomic shape (marks can happen on fork threads via `fail(...)` inside parallel blocks; the check happens on the stage thread after the failure propagates through the fork join):

```scala
  // Written possibly from fork threads (`fail` inside a parallel block), read on
  // the stage thread during unwind — pure atomic state, per the concurrency
  // conventions. Identity comparison: the mark belongs to the object instance.
  private val reportedErrors =
    new java.util.concurrent.atomic.AtomicReference[List[Throwable]](Nil)
  private[orca] def markErrorReported(e: Throwable): Unit =
    reportedErrors.updateAndGet(e :: _).discard
  private[orca] def errorAlreadyReported(e: Throwable): Boolean =
    reportedErrors.get().exists(_ eq e)
```

(check the codebase's discard idiom — `val _ =` vs `.discard`; use what neighboring code uses)
- `fail` (Flow.scala):

```scala
def fail(message: String)(using ctx: FlowContext): Nothing =
  ctx.emit(OrcaEvent.Error(message))
  val e = new OrcaFlowException(message)
  ctx.markErrorReported(e)
  throw e
```

- `runStage`'s catch collapses the three sub-cases and fixes the plain-exception double-report:

```scala
  catch
    case NonFatal(e) =>
      if !fc.errorAlreadyReported(e) then
        e match
          case mao: orca.agents.MalformedAgentOutputException =>
            fc.emit(OrcaEvent.Error(formatMalformedOutput(name, mao)))
          case _ =>
            fc.emit(
              OrcaEvent.Error(
                s"Stage '$name' failed: ${throwableMessage(e, firstLineOnly = true)}"
              )
            )
        fc.markErrorReported(e)
      throw e
```

- `FlowLifecycle.run`'s catch: replace the `fe: OrcaFlowException => fe.alreadyEmitted` match with `if !ctx.errorAlreadyReported(e) then ctx.emit(...)` (and mark afterwards, so anything above the lifecycle — nothing today — stays single-shot).

- [ ] **Step 1: Failing test first** — the hole this fixes: a plain `RuntimeException` thrown in an INNER stage, unwinding through an OUTER stage, currently emits `Error` twice. Write it in the stage-runtime test file (grep for the existing single-emission tests and co-locate; use the file's recording-listener harness): run nested stages where the inner body throws `new RuntimeException("boom")`; count `OrcaEvent.Error` events; expect exactly 1. RED on current code (2).
- [ ] **Step 2: Implement** the interface block; delete `alreadyEmitted` everywhere (`grep -rn "alreadyEmitted" --include=*.scala .` must end empty outside docs); update test contexts (a simple `var reported: List[Throwable]` impl is fine for single-threaded test contexts — mirror TestFlowContext's style).
- [ ] **Step 3: Sweep the pinned behaviors** — existing tests asserting single emission for `fail(...)` and for tool-thrown `OrcaFlowException`s must still pass; `MalformedAgentOutputException` keeps its rich formatting (assert unchanged).
- [ ] **Step 4: Verify** full gate. **Step 5: Commit** — `refactor(flow)!: exactly-once error reporting via a context-owned identity set; drop OrcaFlowException.alreadyEmitted`.

---

### Task 4: Docs + tracker + review wave

- [ ] **Step 1:** `complexity-review.md`: tick 5.1 (Task 2 commit), 5.2 (Task 3 commit), 5.3 (Task 1 commit). AGENTS.md: grep `alreadyEmitted|listener` for stale protocol descriptions; update to the new contracts (total dispatcher; reported-set). Commit — `docs: tick complexity findings 5.1-5.3`.
- [ ] **Step 2: Review wave** over the range: **code-functionality-reviewer** (fable; probes: every error path end-to-end — fail in fork, turn failure, parse retry, listener throw during StageStarted/recordAndCommit windows — count emitted Errors and verify teardown routes unchanged; the deleted rewrap's behavior delta), **scala-fp-reviewer** (opus; the AtomicReference justification vs the no-theatre preference; exception-convention conformance), **simplicity-reviewer**, **test-reviewer** (single-emission matrix coverage: fail/tool-throw/malformed/plain-runtime × direct/nested/boundary).
- [ ] **Step 3:** Triage; ONE batch fixer; re-verify; commit `fix(flow): address review findings on the exception-protocol fixes`; push the branch.
