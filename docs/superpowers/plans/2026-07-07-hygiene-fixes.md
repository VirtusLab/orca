# Hygiene Fixes (findings 8.1, 8.3–8.5, 8.7, 8.8 + listener quarantine) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the remaining §8 hygiene findings and add listener quarantine (owner-requested: a listener that errors is unrecoverable — disable it after its first failure instead of feeding it every subsequent event).

**Architecture:** Seven small tasks: quarantine → vestigial-param deletion → dead checkbox API removal → quiet-subprocess unification → drain state-machine localization → progress-store corruption-as-data → misc small items; tracker + review wave close it.

**Tech Stack:** Scala 3 (braceless), Ox, sbt.

## Global Constraints

- Zero compile warnings; braceless Scala 3; explicit return types on public members.
- `sbt --client` loops; `scalafmtAll` before every commit; full gate per task: `sbt --client "scalafmtAll; compile; Test/compile; test"`.
- Commits end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`; never commit `.superpowers/` or `docs/superpowers/`.
- Branch: continue on `session-identity-fixes`.
- Logging convention (standing): stacks → trace file; console gets one high-level `[orca]` line; either WARN or ERROR is acceptable in the trace (both land there) — content placement is what matters.
- Original finding texts live in `complexity-review.md` §8 — each implementer reads its item first.
- Concurrency preference (standing): no new locks/atomics unless genuinely cross-thread; prefer confinement with a documented comment.

---

### Task 0: Listener quarantine (owner-requested design change)

**Files:** `flow/src/main/scala/orca/events/EventDispatcher.scala`; test `flow/src/test/scala/orca/EventDispatcherTest.scala`

Rationale (from the owner): an event listener that threw is generally unrecoverable — its internal state is suspect, so continuing to deliver events yields repeated throws or misleading half-broken output. The flow itself must survive (5.3's invariant: observation never alters flow control), but the broken listener should be **disabled after its first failure**, announced once.

- [ ] **Step 1 (RED):** REPLACE the existing `"a throwing listener is retried on the next event, not permanently skipped"` test (it pins the now-rejected semantics) with:

```scala
test("a throwing listener is quarantined: announced once, skipped afterwards"):
  val badCalls = new java.util.concurrent.atomic.AtomicInteger(0)
  val bad = new OrcaListener:
    def onEvent(event: OrcaEvent): Unit =
      badCalls.incrementAndGet(); throw new RuntimeException("boom")
  val received = List.newBuilder[String]
  val good = new OrcaListener:
    def onEvent(event: OrcaEvent): Unit = received += "good"
  val dispatcher = new EventDispatcher(List(bad, good))
  dispatcher.onEvent(OrcaEvent.Step("1"))
  dispatcher.onEvent(OrcaEvent.Step("2"))
  assertEquals(badCalls.get(), 1, "quarantined after the first failure")
  assertEquals(received.result(), List("good", "good"), "healthy listeners unaffected")
```

Keep the existing doesn't-stop-others/doesn't-propagate test (still true).
- [ ] **Step 2:** Implement: the dispatcher tracks quarantined listeners. Events can arrive from concurrent emitter threads (reviewer forks), so the set is genuinely cross-thread — use the sanctioned pure-atomic shape:

```scala
  // Listeners that threw are disabled for the rest of the run: an errored
  // observer's state is unrecoverable, so re-delivering events yields repeated
  // throws or misleading half-broken output. Genuinely cross-thread (emitters
  // include reviewer forks) — a concurrent set is the honest primitive.
  private val quarantined =
    java.util.concurrent.ConcurrentHashMap.newKeySet[OrcaListener]()

  def onEvent(event: OrcaEvent): Unit =
    listeners.foreach: l =>
      if !quarantined.contains(l) then
        try l.onEvent(event)
        catch
          case NonFatal(e) =>
            quarantined.add(l).discard
            log.error(<existing message> + " — listener disabled for the rest of the run", e)
            System.err.println(<existing high-level line> + " (listener disabled)")
```

(adapt the existing message/payload construction; announce ONCE — the quarantine guarantees that; keep the Error-payload inclusion). Update the class scaladoc: total + quarantine-on-first-failure semantics; the flow always survives.
- [ ] **Step 3:** Full gate; commit — `feat(events): quarantine a listener after its first failure — unrecoverable observers stop receiving events`.

---

### Task 1: Delete vestigial `(using BufferCapacity)` (finding 8.1)

**Files:** `claude/src/main/scala/orca/tools/claude/ClaudeBackend.scala`, `codex/.../CodexBackend.scala`, `gemini/.../GeminiBackend.scala` (constructor `using` clauses); every construction site the compiler then flags (factories, withDefaults, tests).

- [ ] Verify the claim first: `grep -n "BufferCapacity" <the three files>` — confirm the given is threaded but never used in-module (`ForkedConversation` creates its channel with an explicit capacity precisely so backends needn't carry this — check its comment). Delete the `using` clause from the three constructors; compiler-led sweep of construction sites (delete now-unneeded `given BufferCapacity` in tests/wiring where it existed only for these); opencode/pi already lack it — confirm.
- [ ] Full gate; commit — `refactor(backend): drop the vestigial BufferCapacity threading from claude/codex/gemini constructors`.

---

### Task 2: Remove the dead checkbox API (finding 8.3)

**Files:** `flow/src/main/scala/orca/plan/Task.scala` (`completed`, `markComplete`), `flow/src/main/scala/orca/plan/Plan.scala` (`firstIncomplete`, `markComplete`, checkbox rendering ~:264-274); tests `flow/src/test/scala/orca/plan/PlanTest.scala`, `runner/src/test/scala/flowtests/FlowCompilesTest.scala` (canary may exercise them — grep).

ADR 0018 retired checkbox state as a resume mechanism, but the API survived: `Task.completed` defaults false and NOTHING in the library sets it — `firstIncomplete` always returns the first task, `markComplete` is write-only. It rides every serialized Plan (a hand-edited `completed=true` in a stage log would silently change `firstIncomplete` semantics for user code).

- [ ] Verify deadness: `grep -rn "markComplete\|firstIncomplete\|completed" flow/src/main examples --include=*.scala --include=*.sc | grep -v target` — production callers must be zero (tests + canary only); if an example genuinely uses them, STOP and report. Then DELETE `Task.completed`/`markComplete`, `Plan.firstIncomplete`/`markComplete`; `render` drops the checkbox glyphs (plain `- <title>` bullets — check `parse` round-trips: it must accept BOTH old checkbox lines (`- [ ] `/`- [x] ` prefixes, tolerated for old logs/LLM output) and plain bullets; keep that leniency, document it). Adapt PlanTest (delete the dead-API tests; keep/adjust render/parse round-trip tests) and the canary.
- [ ] JSON compat: old stage logs contain `"completed":false` in serialized plans — the strict LLM codec may reject unknown fields? Plans decode from stage logs via the JsonData codec — check `withSkipUnexpectedFields` behavior in `JsonData.strictCodecConfig`; if strict rejects, a resumed old log's Plan entry would fail decode → stage re-runs (fail-safe, acceptable) — VERIFY which it is and state it in the report + a scaladoc note on Plan.
- [ ] Full gate; commit — `refactor(plan)!: delete the retired checkbox API (completed/markComplete/firstIncomplete)`.

---

### Task 3: One quiet-subprocess stack (finding 8.4)

**Files:** `tools/src/main/scala/orca/subprocess/QuietProc.scala`, `tools/src/main/scala/orca/subprocess/OsProcCliRunner.scala`, `tools/src/main/scala/orca/tools/GitHubTool.scala` (`currentBranchGit` ~:275-284), `tools/src/main/scala/orca/tools/GitTool.scala` (the `nonInteractiveEnv` owner ~:478-483)

Two independent "shell out with captured stderr" implementations exist; `QuietProc`'s doc FALSELY claims `CliRunner` wraps it; and the split already produced divergence: `OsGitTool` runs git under `nonInteractiveEnv` (ssh/credential prompts can't hang a flow) while `OsGitHubTool.currentBranchGit` runs `git rev-parse` through the other stack WITHOUT it.

- [ ] Make `OsProcCliRunner.run` genuinely delegate to `QuietProc.call` (reconcile the parameter conventions — `cwd: os.Path = os.pwd` vs `null`-inherit, `env` empty-vs-null: pick ONE convention, document it, adapt call sites; the doc claim becomes true). Alternatively, if delegation fights the conventions, merge the two into ONE implementation and delete the other — implementer's judgment; acceptance: exactly one `os.proc(...).call` quiet-shell-out site in `orca.subprocess`, docs true.
- [ ] `OsGitHubTool.currentBranchGit`: run under `nonInteractiveEnv` — expose the env from its owner (e.g. `private[tools] val nonInteractiveEnv` on GitTool's companion or a small shared `GitEnv` object in orca.tools) so it has ONE owner; the gh tool's git call uses it. Test: assert the recorded env of the stub CliRunner call includes the non-interactive vars (mirror how OsGitToolTest pins it).
- [ ] Full gate; commit — `refactor(subprocess): one quiet shell-out implementation; gh's git call gains the non-interactive env`.

---

### Task 4: Localize the drain's withheld-turn state machine (finding 8.5)

**Files:** `tools/src/main/scala/orca/backend/Conversations.scala` (`drainAutonomous` ~:27-108); test `tools/src/test/scala/orca/backend/ConversationsTest.scala`

The structured-mode one-turn delay line (`textBuf` + `var withheld` + `closeTurn()` called from the loop AND the finally) splits flush semantics across three sites, and a mid-turn crash silently DROPS the partial buffer (it's prose, not the payload — the "suppress the payload" heuristic quietly became "suppress whatever came last").

- [ ] Extract a named private class in the same file:

```scala
  /** Structured-mode turn buffer: streams every completed turn EXCEPT the most
    * recent one, which is withheld one turn (the final turn is the JSON payload
    * — the caller re-surfaces it via OrcaEvent.StructuredResult). Consequence:
    * turn N renders when turn N+1 completes — a deliberate one-turn display
    * delay, the price of live streaming without showing the payload as prose.
    * In non-structured mode every turn flushes immediately.
    */
  private final class TurnBuffer(structuredMode: Boolean, emit: String => Unit):
    private val current = new StringBuilder
    private var withheld: Option[String] = None
    def append(delta: String): Unit = ...
    def turnEnd(): Unit = ...        // close current; structured: emit withheld, withhold current; else emit current
    /** Normal end of stream: the withheld turn IS the payload — drop it (the
      * caller emits StructuredResult); flush any unfinished current buffer.
      */
    def finishNormally(): Unit = ...
    /** Abnormal end (the drain threw mid-stream): nothing here is reliably the
      * payload — flush EVERYTHING (withheld + partial) rather than silently
      * dropping prose. Worst case the user sees a JSON blob once.
      */
    def finishAbnormally(): Unit = ...
```

`drainAutonomous` wires it: the event loop calls append/turnEnd; the try/catch distinguishes normal completion (`finishNormally()` after the loop) from exception (`finishAbnormally()` in the catch, rethrow). This CHANGES abnormal-path behavior (previously dropped; now flushed) — the finding calls the old behavior a bug; note the delta in the commit message.
- [ ] Tests (ConversationsTest, reuse ScriptedConversation): (a) structured, normal: final turn suppressed, earlier turns emitted (existing pins — keep); (b) structured, mid-turn crash: BOTH the withheld completed turn and the partial buffer are emitted as AssistantMessages (RED on current code); (c) non-structured unchanged.
- [ ] Full gate; commit — `fix(backend): named TurnBuffer for the structured drain; abnormal end flushes instead of dropping prose`.

---

### Task 5: Progress-store corruption as data (finding 8.7)

**Files:** `flow/src/main/scala/orca/progress/ProgressStore.scala`, `runner/src/main/scala/orca/runner/FlowLifecycle.scala` (`setup`); tests `flow/src/test/scala/orca/progress/ProgressStoreTest.scala`, `runner/src/test/scala/orca/runner/FlowLifecycleTest.scala`

Unparseable log ⇒ silent fresh run (a SECOND branch, stages re-run) — currently indistinguishable from no-log-at-all at the `setup` call site.

- [ ] Add to `ProgressStore`:

```scala
  /** Detailed load for the lifecycle's resume decision: distinguishes an
    * absent log (normal fresh run) from a present-but-unparseable one (a
    * corrupt or truncated file — the caller starts fresh but WARNS, because
    * the user may have expected a resume). `load()` keeps its lenient
    * Option shape for the runtime's frequent reads.
    */
  enum LoadResult:
    case Absent
    case Corrupt(reason: String)
    case Loaded(log: ProgressLog)
  def loadDetailed(): LoadResult
```

(`OsProgressStore`: Absent if file missing; parse → Loaded, catch NonFatal(e) → Corrupt(e's message/class); `load()` delegates: `loadDetailed() match { Loaded(l) => Some(l); _ => None }`.) The enum nests in `object ProgressStore` if cleaner for imports — implementer's call, state it.
- [ ] `FlowLifecycle.setup` branches on `loadDetailed()`: `Loaded` → existing resume validation; `Absent` → existing fresh path; `Corrupt(reason)` → emit a visible warning Step (needs an emit path — setup has no ctx… check: setup takes `(args, agent, git, branchNaming, store)`; the WARN goes to the logger at WARN + one `[orca]` stderr line per the convention, since no dispatcher is threaded here — state that choice) then the fresh path.
- [ ] Atomic writes: `OsProgressStore.writeLog` → write to a sibling temp file + `os.move(..., atomicMove = true, replaceExisting = true)` (fallback to plain move if the FS rejects atomic — os-lib handles? check; document). Kills torn-write corruption at the source.
- [ ] Tests: Corrupt file (garbage bytes) → `loadDetailed()` is `Corrupt`, `load()` is None (unchanged); FlowLifecycleTest: corrupt log → run proceeds fresh AND the warning is observable (stderr capture or log — match what the test harness can see; if neither is cheaply observable, pin `loadDetailed` at the store level and note it).
- [ ] Full gate; commit — `feat(progress): corruption-as-data (loadDetailed); atomic log writes; corrupt-log warning at setup`.

---

### Task 6: Misc small items (finding 8.8)

**Files:** `opencode/src/main/scala/orca/tools/opencode/OpencodeConversation.scala` + `tools/src/main/scala/orca/backend/ForkedConversation.scala`; `pi/src/main/scala/orca/tools/pi/PiBackend.scala`; `tools/src/main/scala/orca/agents/Agent.scala` (docs)

- [ ] **isSettled dedup:** OpencodeConversation keeps its own `settled` flag beside the base's `settledOutcome` (now a plain var). Expose `protected def isSettled: Boolean = settledOutcome.isDefined` on `ForkedConversation`; delete the subclass flag; opencode's post-terminal-frame drops use `isSettled`. Verify the asymmetric set in `finishTurn`/`failTurn` disappears with the flag. Opencode conversation tests are the net.
- [ ] **Pi resource list:** `PiBackend.openConversation` (~:117-131 pre-refactor — grep) appends the system-prompt file to a mutable `ListBuffer` INSIDE the spawn thunk, relying on `SubprocessSpawn.open`'s by-name `resources` param. Allocate the system-prompt file BEFORE calling `open` so `resources` is a plain immutable `List` built up-front; delete the by-name-dependency comments on both sides IF pi was the last such user (grep other backends for the same pattern first — if claude/codex also append inside thunks, leave the by-name mechanism documented and only fix pi).
- [ ] **Silent no-op builder docs:** `Agent.withCheapModel` (returns `this` by default — a pinned cheap model silently ignored on a custom Agent) and `withSelfManagedGit` (deliberate fail-safe no-op): making them abstract breaks every test stub for marginal value — instead make the scaladoc explicit and LOUD on both ("on an Agent that doesn't override this, the call is a silent no-op — BaseAgent-derived tools override it; custom Agent implementations must too if they want the behavior"). `withSelfManagedGit` already documents its no-op rationale — align `withCheapModel`'s doc to the same clarity.
- [ ] **`isSafeSessionId` placement:** move it into `object SessionId` as `SessionId.isSafe(id: String)` (same file, `tools/src/main/scala/orca/agents/BackendTag.scala`) — compiler-led call-site sweep (SessionSupport, Conversations guard, probes, tests). Keep a deprecated-free clean break (internal API).
- [ ] Full gate; ONE commit — `refactor: isSettled on the base conversation; eager pi resources; SessionId.isSafe; louder no-op builder docs`.

---

### Task 7: Tracker + review wave

- [ ] `complexity-review.md`: tick 8.1, 8.3, 8.4, 8.5, 8.7, 8.8 with their commits; 8.8's conditional GitPushAuth item noted as deliberately deferred ("if it grows"). Add a line under the §5.3/P5 note that listener QUARANTINE landed (owner decision). Commit — `docs: tick complexity findings 8.x; note listener quarantine`.
- [ ] **Review wave** over the whole range: **code-functionality-reviewer** (fable; probes: quarantine under concurrent emitters — first-failure race double-announce?; TurnBuffer normal/abnormal matrix incl. the interaction with the wire-id guard's throw [is the guard's throw an "abnormal end" for the buffer? it throws AFTER the drain loop — trace]; atomic-move fallback; checkbox-API removal vs old-log decode), **scala-fp-reviewer** (opus; quarantine set justification; TurnBuffer confinement; LoadResult shape), **simplicity-reviewer** (sonnet), **test-reviewer** (sonnet; the flipped quarantine pin; TurnBuffer crash tests; corrupt-log observability).
- [ ] Triage; ONE batch fixer; re-verify; commit `fix: address review findings on the hygiene range`; push the branch.
