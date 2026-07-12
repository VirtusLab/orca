# Capability Machinery & Latent Bugs (findings 6.1–6.5, 7.1, 7.3–7.6, 7.8, 7.9) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the remaining §6 capability-machinery findings and §7 latent bugs, plus correct the error-logging levels per the owner's convention.

**Architecture:** Ten small-to-medium tasks in risk order: logging levels → prompter lifecycle → backend hygiene trio → structured-result fallback → claude flag split + iterator de-trick → agent-override factories (API break) → InStage mint funnel → JsonData given naming → docs/tracker → review wave.

**Tech Stack:** Scala 3 (braceless), Ox, JLine, sbt.

## Global Constraints

- Zero compile warnings; braceless Scala 3; explicit return types on public members.
- `sbt --client` loops; `scalafmtAll` before every commit; full gate per task: `sbt --client "scalafmtAll; compile; Test/compile; test"`.
- Commits end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`; never commit `.superpowers/` or `docs/superpowers/`.
- Branch: continue on `session-identity-fixes`.
- **Logging convention (owner-stated, binding):** legitimate errors log at ERROR (full detail/stack → the trace file via the `orca.*` logger); the terminal shows one high-level line (no bare exit codes, no stack traces). WARN is for degraded-but-not-broken.
- User decisions (binding): 7.8 = factory override params (`Option[AgentWiring => XAgent]`, breaking); 6.3 = `Option[AgentConfig]` (omission structural, Some still wholly replaces); 6.1 = lean IN on tags (plans 1–4 invested in them; document the helper currency, do NOT untag).
- Original finding texts with refs live in `/home/adamw/orca/complexity-review.md` §6–§7 — each task's brief cites its item; the implementer should read that item first.

---

### Task 0: Error-logging levels (owner correction to the P5 stderr fix)

**Files:** `flow/src/main/scala/orca/events/EventDispatcher.scala`, `runner/src/main/scala/orca/runner/DefaultFlowContext.scala` (close-all loop), `runner/src/main/scala/orca/runner/OrcaLog.scala` (scaladoc only)

- [ ] `EventDispatcher.onEvent`'s catch: `log.warn` → `log.error` (a listener failure is a legitimate error; full stack belongs in the trace file at ERROR). The existing one-line `System.err` announcement stays exactly as the high-level console line (verify it prints NO stack — message + event type + Error payload only). Update the comment/scaladoc to state the convention: ERROR+stack → trace; one high-level line → stderr.
- [ ] `DefaultFlowContext.close()`'s per-agent catch: `log.warn` → `log.error` with the same split — add a one-line `System.err.println(s"[orca] failed to close agent ...: ${e.getMessage}")` (a leaked backend process is user-relevant); stack stays in the log call.
- [ ] `OrcaLog` scaladoc: add two sentences codifying the convention (trace file = full diagnostics incl. stacks, all levels; console = high-level lines only — WARN+ from logback where additive, plus deliberate `[orca]` stderr lines for cases the logger can't reach).
- [ ] Adjust the P5 tests if any asserted the WARN level; full gate; commit — `fix(events): listener/agent-close failures log at ERROR; codify the console-vs-trace convention`.

---

### Task 1: `JLinePrompter` lifecycle (finding 7.1)

**Files:** `runner/src/main/scala/orca/runner/terminal/ConversationRenderer.scala` (:51 default param, :88 `finally closePrompter()`, :243 `closePrompter`, :318 `object JLinePrompter`), `runner/src/main/scala/orca/runner/terminal/TerminalInteraction.scala` (close path)
**Test:** `runner/src/test/scala/orca/runner/terminal/ConversationRendererTest.scala`

The bug (complexity-review 7.1): `render`'s `finally closePrompter()` closes the PROCESS-GLOBAL `JLinePrompter` after every conversation — the first close force-allocates the lazy terminal just to close it, and every later interactive prompt in the run operates on closed I/O.

- [ ] Restructure to process-scoped lifecycle: delete `closePrompter` and the `finally` at :88 (a renderer never closes its prompter); `JLinePrompter` gains an initialization guard so `close()` NEVER forces the lazy:

```scala
  object JLinePrompter extends Prompter:
    // Guard so close() never forces the lazy terminal: pure non-interactive
    // runs must never allocate one (that laziness is the object's whole point).
    @volatile private var opened = false
    private lazy val terminal: Terminal =
      opened = true
      <existing builder>
    ...
    def close(): Unit = if opened then terminal.close()
```

  (`@volatile`: opened is set under the lazy-init lock but read from whatever thread calls close — keep it simple and correct.)
- [ ] `TerminalInteraction.close()` closes the prompter once at interaction teardown (after its worker drains): call `JLinePrompter.close()` — or better, thread the renderer's `prompter` reference so a custom Prompter injected in tests gets closed instead of hardcoding the singleton (look at how TerminalInteraction constructs renderers at ~:55-62 and pick the honest wiring; `Prompter` may need `close(): Unit = ()` on the trait).
- [ ] Tests: (a) RED first — a recording stub `Prompter` is NOT closed by `render` (currently it is); (b) the interaction's close() closes it exactly once; (c) two sequential `render`+prompt cycles against one stub prompter both reach `ask` (pins the second-prompt-usable property).
- [ ] Full gate; commit — `fix(terminal): prompter is process-scoped — renderers stop closing it; close() never force-allocates`.

---

### Task 2: Backend hygiene trio (findings 7.3, 7.4, 7.5)

**Files:** `codex/src/main/scala/orca/tools/codex/CodexBackend.scala` (`writeSchemaIfPresent` ~:214-221 pre-refactor — grep current), `tools/src/main/scala/orca/backend/Conversations.scala` (`drainAndCommit`), `claude/src/main/scala/orca/tools/claude/ClaudeBackend.scala` + `runner/src/main/scala/orca/runner/DefaultFlowContext.scala` (claude arm)
**Test:** `codex/src/test/scala/orca/tools/codex/CodexBackendTest.scala`, `tools/src/test/scala/orca/backend/ConversationsTest.scala`, `claude/src/test/scala/orca/tools/claude/ClaudeBackendTest.scala`

- [ ] **7.3 codex schema file:** replace the fixed `workDir/.codex/orca-output-schema.json` with a unique temp file OUTSIDE the working tree: `os.temp(schemaJson, prefix = "orca-codex-schema-", suffix = ".json", deleteOnExit = false)` deleted via the conversation's finalize resources (find how claude registers its MCP-config file for deletion in `SubprocessSpawn.open`'s resources and mirror it — codex spawns through the same helper). Kills: the mapParUnordered race (unique file per call), the `git add -A` commit pollution, and the never-cleaned artifact. Test: two schema files from two calls differ; the file is gone after the conversation finalizes; nothing written under workDir.
- [ ] **7.4 empty/unsafe wire-id guard:** in `drainAndCommit`, before `commitSuccess`:

```scala
    val wire = WireSessionId.value(result.wireId)
    if !isSafeSessionId(wire) then
      throw new OrcaFlowException(
        s"backend reported an invalid session id ('$wire') — refusing to record it for resume"
      )
```

  (plain `OrcaFlowException` = retryable: a fresh attempt may get a healthy init event; the registry stays clean either way — document that choice at the site.) Test in ConversationsTest: a result with `wireId = WireSessionId("")` → throws, `registry.resumeWireId(client).isEmpty`.
- [ ] **7.5 claude probe cwd:** `DefaultFlowContext.withDefaults`' claude arm passes `cwdForProbe = workDir` to `new ClaudeBackend(...)` (the param exists with default `os.pwd`; worktree flows currently probe the WRONG slug and silently re-seed every resume). Update `cwdForProbe`'s scaladoc: it must be the workDir agents SPAWN with — the runtime passes the flow's workDir; the `os.pwd` default serves only bare/test construction. Test: a ClaudeBackendTest existence probe against a transcript under a NON-pwd workDir slug (construct with `cwdForProbe = tempDir`, create `projectsDir/<slug-of-tempDir>/<id>.jsonl`, claim, assert exists).
- [ ] Full gate; ONE commit — `fix(backend): codex schema via temp file; refuse unsafe wire ids; claude probe follows the flow workDir`.

---

### Task 3: Structured-result raw fallback (finding 7.6)

**Files:** `runner/src/main/scala/orca/runner/terminal/TerminalEventListener.scala` (:62 `case OrcaEvent.StructuredResult(_, summary)` — currently `summary.foreach(...)` only), consult `adr/0008-terminal-output-design.md` (the `▶`/`●` glyph contract)
**Test:** `runner/src/test/scala/orca/runner/terminal/TerminalEventListenerTest.scala`

The bug: the renderer suppresses the streamed JSON in structured mode, and the listener renders only when an `Announce[O]` summary exists — `resultAs[O]` without an `Announce` produces NO terminal output for the agent's answer. ADR 0008 specifies the fallback: summary as `▶` when present, RAW (truncated) under `●` when not.

- [ ] Implement per the ADR: `summary` present → current `▶` rendering unchanged; `summary` absent → render the `raw` payload in the assistant-message (`●`) style, single-line-collapsed and truncated (~200 chars + `…` — reuse/mirror the truncation idiom in `formatMalformedOutput`/`ToolInputSummary` — pick the existing helper if one fits). Comment: this is the ADR 0008 fallback; suppression at the renderer is safe ONLY because this listener guarantees a visible result either way.
- [ ] Tests: (a) RED — no summary → currently zero output; after: truncated raw rendered; (b) summary present → summary rendered, raw NOT (unchanged pin if missing); (c) long raw truncated with ellipsis.
- [ ] Full gate; commit — `fix(terminal): StructuredResult without an Announce renders the truncated raw payload (ADR 0008 fallback)`.

---

### Task 4: Claude flag split + iterator de-trick (findings 7.9, 6.4 residue)

**Files:** `claude/src/main/scala/orca/tools/claude/ClaudeConversation.scala` (:57 `deltasSinceTurnBoundary`, writes :106, snapshot/reset :125-126, error heuristic :200), `tools/src/main/scala/orca/backend/ForkedConversation.scala` (:352-366 `peeked: Option = null` tri-state)
**Test:** `claude/src/test/scala/orca/tools/claude/ClaudeConversationTest.scala`, existing `ConversationsTest`/backend suites as the iterator net

- [ ] **7.9:** one flag carries two meanings — (a) the re-emit fallback gate consumed in `handleAssistantTurn` (:125-126 snapshot+reset) and (b) the error-display heuristic (:200: deltas since the last full turn ⇒ the error body itself streamed ⇒ show "see message above"). Split into two clearly-named driver-local vars per the finding: keep the (a) mechanics under a name like `partialsSeenThisTurn`, and give (b) its own boolean written at delta time and consumed only in `handleResultError` (e.g. `deltasSinceLastFullTurn`). Each gets a one-line comment naming its single consumer and the wire-ordering fact it relies on. Behavior identical — existing scripted tests must pass unchanged; add one test ONLY if the (b) path (`is_error` after streamed deltas → short message) is currently unpinned (grep the test file first).
- [ ] **6.4 residue:** replace the `null`-as-third-state `Option` in `channelIterator` with an explicit private enum:

```scala
      private enum Peek:
        case Empty                            // nothing buffered; next hasNext blocks
        case Ready(event: ConversationEvent)  // buffered, not yet consumed
        case Closed                           // stream ended; hasNext false forever
      private var peek: Peek = Peek.Empty
```

  with `hasNext`/`next()` rewritten over the three cases (same semantics; reader-thread-confined var, same as today). The lazy-fork/two-phase-start design is explicitly NOT in scope (documented and load-bearing; the rest of 6.4 was already fixed by the concurrency-audit task).
- [ ] Full gate; ONE commit — `refactor(conversation): split claude's dual-purpose delta flag; explicit Peek state for the event iterator`.

---

### Task 5: Agent-override factories (finding 7.8 — breaking, user-approved)

**Files:** Create `tools/src/main/scala/orca/backend/AgentWiring.scala`; modify `runner/src/main/scala/orca/runner/FlowWiring.scala`, `runner/src/main/scala/orca/flow.scala` (public `flow` params), `runner/src/main/scala/orca/runner/DefaultFlowContext.scala` (withDefaults arms), each backend module (public default-agent factory)
**Test:** `runner/src/test/scala/orca/runner/OrcaOverridesTest.scala` (the override-plumbing pin suite), `runner/src/test/scala/flowtests/FlowCompilesTest.scala`

**Interfaces:**

```scala
package orca.backend

/** Everything the runtime wires into an agent at construction: the run's event
  * sink (costs/steps land in the tracker and terminal), the interaction, the
  * working directory, and the prompt templates. Override factories receive
  * this so user-supplied agents are first-class citizens of the run — the old
  * `Option[Agent]` overrides were constructed before the dispatcher existed
  * and were silently event-blind (complexity-review 7.8).
  */
final case class AgentWiring(
    events: OrcaListener,
    interaction: Interaction,
    workDir: os.Path,
    prompts: Prompts
)
```

- `flow(...)`/`FlowWiring` override params become `claude: Option[AgentWiring => ClaudeAgent] = None` (same for codex/opencode/pi/gemini). A caller with a prebuilt agent writes `Some(_ => myAgent)`; the normal case is `Some(w => ClaudeAgents.default(w).opus)`.
- Each backend module gains a small PUBLIC factory object mirroring what `withDefaults` builds today, e.g. in claude: `object ClaudeAgents { def default(wiring: AgentWiring): ClaudeAgent = ... }` (constructing `ClaudeBackend`/`DefaultClaudeAgent` internally — those classes STAY `private[orca]`; the factory is the public surface). `withDefaults`' arms then call THE SAME factories (`wiring.claude.map(_(agentWiring)).getOrElse(ClaudeAgents.default(agentWiring))` — with claude's Opus1M default config and gemini's Pro pin moving INTO the factories so defaults are identical either way). Opencode's factory needs the launcher: `OpencodeAgents.default(wiring, launcher: OpencodeLauncher = OpencodeLauncher.default)` — `withDefaults` passes `wiring.opencodeLauncher`.

- [ ] RED: rewrite `OrcaOverridesTest`'s agent-override cases to the factory shape AND add the new pin: an override built via the factory RECEIVES the run's dispatcher — its `TokensUsed` reaches an `extraListeners` recorder (the exact event-blindness 7.8 describes). Adapt FlowCompilesTest if it exercises overrides.
- [ ] Implement; export `AgentWiring` + the five factory objects on the user surface (check `runner/src/main/scala/orca/exports.scala` for the export pattern).
- [ ] Full gate; commit — `feat(runner)!: agent overrides are factories receiving AgentWiring; public per-backend default-agent factories`.

---

### Task 6: `Option[AgentConfig]` (finding 6.3)

**Files:** `tools/src/main/scala/orca/agents/AgentConfig.scala` (delete the `default` sentinel + its scaladoc), `tools/src/main/scala/orca/agents/BaseAgent.scala` (`effectiveConfig`), `tools/src/main/scala/orca/agents/AgentCall.scala` (run signatures)
**Test:** compiler-led sweep + `tools`/`claude` call tests

- [ ] Every `run(..., callConfig: AgentConfig = AgentConfig.default)` becomes `config: Option[AgentConfig] = None`; `effectiveConfig(callConfig: Option[AgentConfig]): AgentConfig = callConfig.getOrElse(config)` (tool-level fallback; an explicit `Some` still WHOLLY replaces — keep that sentence in the scaladoc). Delete `AgentConfig.default` and its eq-detection scaladoc; grep `AgentConfig.default` across main+tests — internal construction sites use `AgentConfig()` directly now (the trap is gone, so a fresh instance is fine); call sites passing configs wrap in `Some`.
- [ ] Add the one test the sentinel made impossible: `run(config = Some(AgentConfig()))` genuinely replaces the tool config (previously `AgentConfig()` silently matched-by-value-not-reference and… actually previously it WIPED; now it wipes EXPLICITLY — pin that `Some(AgentConfig())` uses the fresh config, and that `None` (omission) uses the tool config).
- [ ] Full gate; commit — `refactor(agents)!: per-call config is Option[AgentConfig]; the eq-sentinel dies`.

---

### Task 7: InStage mint funnel + capture-plan amendment (finding 6.2)

**Files:** Create `flow/src/main/scala/orca/RuntimeInStage.scala`; modify the 7 mint sites (`flow/src/main/scala/orca/Flow.scala` ×2, `flow/src/main/scala/orca/Session.scala` ×2, `runner/src/main/scala/orca/runner/FlowLifecycle.scala` ×3 — re-grep `InStage.unsafe` for the current count), `tools/src/main/scala/orca/InStage.scala` (scaladoc + implicitNotFound), `adr/0018-stage-bound-flow-runtime.md` (§6 amendment)

- [ ] Create the funnel:

```scala
package orca

/** The runtime's ONE named door to a forged [[InStage]] token. Every
  * privileged mint (stage bookkeeping, session recording, lifecycle
  * setup/teardown) calls [[token]], so auditing "who can mutate outside a
  * stage?" is a single grep for `RuntimeInStage` — the call sites ARE the
  * whitelist. Library code must never call `InStage.unsafe` directly.
  */
private[orca] object RuntimeInStage:
  def token(): InStage = InStage.unsafe
```

  Switch all mint sites to `given InStage = RuntimeInStage.token()`; `InStage.unsafe`'s scaladoc says "called only by RuntimeInStage; see it for the whitelist" (grep `InStage.unsafe` afterward: definition + RuntimeInStage + tests only — check what tests mint and leave them on `unsafe` or the funnel, whichever their comments suggest; tests minting directly is acceptable, note it).
- [ ] `InStage`'s `@implicitNotFound` gains the helper-author clause: "…If this is a helper meant to run inside a stage, declare it `(using InStage)` so its caller's token flows through."
- [ ] ADR 0018 §6 amendment (dated): the capture-checking endgame as written would outlaw the reviewer fan-out's deliberate, load-bearing capture of `InStage` into `mapParUnordered` forks; amend to distinguish index-mutating capabilities (must NOT cross forks) from LLM-call gating (must), e.g. via a future `Sharable` sub-capability — decision deferred, constraint recorded.
- [ ] Full gate (`InStageNegativeTest` must stay green); commit — `refactor(flow): funnel runtime InStage mints through RuntimeInStage; helper-author diagnostic; ADR 0018 §6 amendment`.

---

### Task 8: JsonData given naming (finding 6.5)

**Files:** `tools/src/main/scala/orca/agents/JsonData.scala` (:64-71 primitives, :134-137 bridges), `tools/src/main/scala/orca/agents/BackendTag.scala` (:57-66 the `given_JsonData_String` synthesized-name reference)

- [ ] Name every anonymous primitive given (`given stringJsonData: JsonData[String]`, `intJsonData`, etc. — enumerate what's there); `BackendTag`'s `SessionId` given references `JsonData.stringJsonData` (the synthesized-name hazard dies). Keep the "don't summon here" loop-guard comments (still true); if scoping the bridge givens into an importable object is a ≤20-line change that keeps all call sites compiling with at most an import, do it; otherwise leave bridges + note why in the report.
- [ ] Full gate (JsonData tests + schema tests are the net); commit — `refactor(agents): named JsonData primitive givens; kill the synthesized-name reference`.

---

### Task 9: Docs, tracker, review wave

- [ ] **6.1 (lean-in docs):** `FlowContext.LeadB` scaladoc + AGENTS.md helper-authoring guidance: helpers that thread sessions take `[B <: BackendTag]` with explicit `Agent[B]`/`SessionId[B]` params (or a `Sessioned[B]` pair — point at `orca.plan.Sessioned`); the path-dependent accessor is for straight-line flow bodies. One paragraph each, cross-referenced.
- [ ] Tracker: tick 6.1–6.5, 7.1, 7.3–7.6, 7.8, 7.9 with their commits; note the logging-convention correction under the P5 entry or 5.3.
- [ ] Commit — `docs: helper-authoring guidance for LeadB; tick findings 6.x and 7.x`.
- [ ] **Review wave** over the whole range: **code-functionality-reviewer** (fable; probes: prompter double-close/never-close matrix across interactive+non-interactive runs; the wire-id guard's retry interaction; override factories receiving the REAL dispatcher; schema temp-file lifecycle under parallel calls), **scala-fp-reviewer** (opus; AgentWiring shape, Option[AgentConfig] semantics, Peek enum, funnel hygiene), **simplicity-reviewer** (factory surface minimal? funnel earning its keep?), **test-reviewer** (the new pins non-vacuous; OrcaOverridesTest still the override net).
- [ ] Triage; ONE batch fixer; re-verify; commit `fix: address review findings on the capability/bug-fix range`; push the branch.
