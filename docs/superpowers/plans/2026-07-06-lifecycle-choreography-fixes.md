# Shutdown & Lifecycle Choreography Fixes (findings 3.1–3.6) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give backend shutdown a uniform owner (`close()` on the SPI), collapse the opencode once-init side-channels, flatten the flow exit path, tunnel the override knobs through one `FlowWiring` value, and make `FlowLifecycle` the single owner of the phase protocol.

**Architecture:** Five bounded refactors in dependency order: exit-path flattening (pure local) → `FlowWiring` (mechanical signature collapse) → opencode eager-server construction (kills the CAS side-channels) → SPI-wide `close()` (kills `closeHook`/`var closeContext`) → `FlowLifecycle.run` (single phase owner). Docs + tracker + a 4-agent review wave close it.

**Tech Stack:** Scala 3 (braceless), Ox structured concurrency, sbt multi-module.

## Global Constraints

- Zero compile warnings; braceless Scala 3; explicit return types on public members.
- `sbt --client` loops; `sbt --client scalafmtAll` before every commit; full gate per task: `sbt --client "scalafmtAll; compile; Test/compile; test"`.
- Commits end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`; never commit `.superpowers/` or `docs/superpowers/`.
- Branch: continue on `session-identity-fixes`.
- The PUBLIC `flow(...)` signature does not change (ADR 0005 mandates the named-args-first-list shape; `opencodeLauncher` stays a public param — it is configuration, not lifecycle). Only the private plumbing collapses.
- **Design decision (binding, from research):** `OpencodeServer`'s internal atomics (`processRef`/`stopped` + the post-spawn `stopped` re-check) are NOT to be replaced with a `synchronized` monitor. `shutdown()` must run concurrently with a `start()` blocked in a non-interruptible native read (destroying the process is what unblocks it); a shared monitor would deadlock exactly there. The accidental complexity to remove is one level up: `OpencodeBackend`'s `serverRef` AtomicReference + `firstWorkDir` CAS + `sharedServer` lazy.
- `FlowLifecycleTest` (713 lines) pins crash→resume end-to-end via the `runFlow` seam — it is the regression net for Tasks 1, 4, 5 and must pass with only signature-level adaptations.

---

### Task 1: Flatten the exit paths (finding 3.3)

**Files:**
- Modify: `runner/src/main/scala/orca/flow.scala:119-158` (the `flow` wrapper), `:276-295` (`runFlow`'s `bodySucceeded`)
- Test: existing `runner/src/test/scala/orca/runner/FlowLifecycleTest.scala` (behavior net; no new tests needed — this is control-flow-shape only)

**Interfaces:** none new; `flow`/`runFlow` signatures unchanged in this task.

- [ ] **Step 1: Flatten `flow`'s wrapper.** Replace the nested try/catch-with-duplicated-cleanup:

```scala
  var failed = false
  try
    try
      runFlow(...same args...)(body)
    catch
      // The failure was already surfaced inside the scope (the flow body runs
      // as a top-level stage). Only the exit code remains to decide — after
      // the finally below has printed the summary and detached the trace.
      case NonFatal(_) => failed = true
  finally
    costTracker.printSummary()
    orcaLog.finish()
  if failed then System.exit(1)
```

One cleanup site; `System.exit(1)` moves AFTER the `finally`, so the duplicated `printSummary()/finish()` pair in the old catch is deleted. A fatal throwable still hits the `finally` then kills the JVM. Keep the surrounding comments that still apply (fatal-throwable rationale); delete the "skips the outer finally" comment — it no longer does.
- [ ] **Step 2: Delete `bodySucceeded` in `runFlow`.** The catch rethrows, so code after the try/catch already runs only on success:

```scala
      try
        body(using ctx)
      catch
        case NonFatal(e) =>
          ...unchanged failure handling...
          FlowLifecycle.teardownFailure(effectiveGit)
          throw e
      FlowLifecycle.teardownSuccess(effectiveGit, setup, returnToStartBranch)
```

Update the "Teardown separation" comment: the disjointness is now structural (the catch rethrows; success teardown is unreachable on failure) rather than flag-guarded.
- [ ] **Step 3: Verify** — full gate; FlowLifecycleTest green unchanged. **Step 4: Commit** — `refactor(runner): flatten flow exit paths; drop the bodySucceeded flag`.

---

### Task 2: `FlowWiring` — collapse the override tunnel (finding 3.6)

**Files:**
- Create: `runner/src/main/scala/orca/runner/FlowWiring.scala`
- Modify: `runner/src/main/scala/orca/flow.scala` (`flow` packs; `runFlow` consumes), `runner/src/main/scala/orca/runner/DefaultFlowContext.scala` (`withDefaults` consumes)
- Test: adapt `runFlow` callers — `runner/src/test/scala/orca/runner/FlowLifecycleTest.scala`, `FlowContextAgentTest.scala`, `OrcaOverridesTest.scala`, `OpencodeFlowTest.scala` (grep `runFlow(` for the full list)

**Interfaces:**
- Produces:

```scala
package orca.runner

/** The per-run tool/agent override bundle `flow(...)` collects from its named
  * arguments. One value tunnels through `runFlow` → `withDefaults` instead of
  * ten positional parameters repeated at each layer; adding a backend is one
  * field here plus one `getOrElse` in `withDefaults`.
  */
private[orca] case class FlowWiring(
    claude: Option[ClaudeAgent] = None,
    codex: Option[CodexAgent] = None,
    opencode: Option[OpencodeAgent] = None,
    opencodeLauncher: OpencodeLauncher = OpencodeLauncher.default,
    pi: Option[PiAgent] = None,
    gemini: Option[GeminiAgent] = None,
    git: Option[GitTool] = None,
    gh: Option[GitHubTool] = None,
    fs: Option[FsTool] = None,
    prompts: Prompts = DefaultPrompts
)
```

- `runFlow(args, agent, workDir, interaction, extraListeners, branchNaming, returnToStartBranch, progressStore, wiring: FlowWiring)(body)`; `DefaultFlowContext.withDefaults(userPrompt, dispatcher, workDir, interaction, progressStore, agentSelector, wiring: FlowWiring)`.

- [ ] **Step 1:** Create `FlowWiring.scala` (imports from `orca.agents`, `orca.tools`, `orca.tools.opencode.OpencodeLauncher`).
- [ ] **Step 2:** `flow(...)` body packs `FlowWiring(claude = claude, codex = codex, opencode = opencode, opencodeLauncher = opencodeLauncher, pi = pi, gemini = gemini, git = git, gh = gh, fs = fs, prompts = prompts)` and passes it to `runFlow`. The public signature is untouched.
- [ ] **Step 3: Single git-default site.** In `runFlow`, DELETE the early `val effectiveGit = git.getOrElse(new OsGitTool(workDir, dispatcher))` — `withDefaults`' own `wiring.git.getOrElse(new OsGitTool(workDir, dispatcher))` becomes the ONLY default site (the duplication was flagged as a drift seed). The lifecycle calls that used `effectiveGit` (`FlowLifecycle.setup(...)`, `teardownFailure`, `teardownSuccess`) switch to `ctx.git` — the context is constructed before setup runs, and it holds the same resolved instance. Update the "Resolve the git tool up-front" comment accordingly (it is now resolved inside the context, read via `ctx.git`).
- [ ] **Step 4:** `withDefaults` replaces its ten override params with `wiring: FlowWiring`; body reads `wiring.claude.getOrElse(...)` etc. Delete the now-shadowed defaults on the deleted params.
- [ ] **Step 5:** Adapt test callers (mostly: fewer `None` positional args — most already use named args and simply move them into `FlowWiring(...)` or drop them since the wiring defaults cover them).
- [ ] **Step 6: Verify** full gate. **Step 7: Commit** — `refactor(runner): tunnel tool overrides through one FlowWiring value; single git-default site`.

---

### Task 3: Opencode eager-server construction (finding 3.2)

**Files:**
- Modify: `opencode/src/main/scala/orca/tools/opencode/OpencodeBackend.scala` (apply + class head + `server(workDir)` call sites + probe), `opencode/src/main/scala/orca/tools/opencode/OpencodeServer.scala` (implements the new handle trait + `started`)
- Modify: `runner/src/main/scala/orca/runner/DefaultFlowContext.scala` (construction site)
- Test: `opencode/src/test/scala/orca/tools/opencode/OpencodeBackendTest.scala`, `OpencodeServerTest.scala`, `runner/.../EnforcementTableTest.scala`, `OpencodeFlowTest.scala` (all construct the backend — adapt to the new seam)

**Interfaces:**
- Produces (in `OpencodeBackend.scala` or a small adjacent file):

```scala
/** Lifecycle seam between the backend and the shared `opencode serve` owner —
  * lets tests substitute a fake without a real process. `http` may spawn on
  * first force; `started` must never spawn (probes use it to answer "absent"
  * without side effects); `close()` is idempotent.
  */
private[opencode] trait OpencodeServerHandle:
  def http: OpencodeHttp
  def started: Boolean
  def close(): Unit
```

- `OpencodeServer` implements it (`started: Boolean = clientRef.get() != null`; `close()` aliases the existing `shutdown()`).
- `class OpencodeBackend(server: OpencodeServerHandle)`; `OpencodeBackend.apply(cli: CliRunner, workDir: os.Path, launcher: OpencodeLauncher = OpencodeLauncher.default)(using Ox): OpencodeBackend = new OpencodeBackend(new OpencodeServer(cli, workDir, launcher))`.
- DELETED: `serverRef`, `onShutdown`/`httpFor` constructor params, `firstWorkDir`, `sharedServer`, `private def server(workDir)`. The backend's `shutdown()` becomes `override def close(): Unit = server.close()` in Task 4 — in THIS task keep the method named `shutdown()` delegating to `server.close()` so the runner wiring compiles unchanged.

- [ ] **Step 1: Failing/adapted tests first.** `OpencodeBackendTest` stubs currently inject `httpFor: os.Path => OpencodeHttp` — rewrite the stubs as `OpencodeServerHandle` fakes (`http` returns the fake client; `started` a settable flag; `close()` recorded). Add one new test: `"sessionExists is false when the server was never started (no spawn)"` — a handle whose `http` throws (`fail("must not spawn")`) and `started = false`; probe returns false. RED: trait not found.
- [ ] **Step 2: Implement** the trait + `OpencodeServer.started`/`close` + the constructor change; `runAutonomous`/`runInteractive` use `server.http` directly (workDir no longer selects the server — it is fixed at construction; the per-call `workDir` SPI param remains but opencode's scaladoc already documents the constant-workDir assumption — keep that note, now at the `apply`). The existence probe becomes:

```scala
  val sessions: SessionSupport[BackendTag.Opencode.type] =
    SessionSupport.Durable(
      registry,
      id => server.started && probeSession(id, server.http)
    )
```

- [ ] **Step 3:** `DefaultFlowContext.withDefaults`: the opencode arm becomes `OpencodeBackend(OsProcCliRunner, workDir, wiring.opencodeLauncher)` (the `(backend, close-hook)` tuple survives until Task 4 removes it — keep `(a, () => backend.shutdown())` shape for now).
- [ ] **Step 4:** Adapt `EnforcementTableTest`'s opencode construction (`new OpencodeBackend(<throwing handle>)`).
- [ ] **Step 5: Verify** full gate (OpencodeServerTest/OpencodeFlowTest green). **Step 6: Commit** — `refactor(opencode): eager server construction behind OpencodeServerHandle; drop the once-init side-channels`.

---

### Task 4: Uniform `close()` on the SPI; kill `closeHook` and `var closeContext` (finding 3.1)

**Files:**
- Modify: `tools/src/main/scala/orca/backend/AgentBackend.scala`, `tools/src/main/scala/orca/agents/Agent.scala`, `tools/src/main/scala/orca/agents/BaseAgent.scala`
- Modify: `opencode/src/main/scala/orca/tools/opencode/OpencodeBackend.scala` (rename `shutdown()` → `override def close()`)
- Modify: `runner/src/main/scala/orca/runner/DefaultFlowContext.scala` (close-all; delete `closeHook` + the opencode tuple), `runner/src/main/scala/orca/flow.scala` (nested finally replaces `var closeContext`)
- Test: `tools` — small new coverage in an existing agent test file; `runner/.../OpencodeFlowTest.scala` (pins the serve teardown — must stay green)

**Interfaces:**
- Produces: `AgentBackend.close(): Unit = ()` — scaladoc: *"Release background resources this backend owns (processes, servers, drain forks). Called by the runtime in the flow body's `finally`, BEFORE the flow scope joins its forks — a resource whose teardown unblocks a non-interruptible read must happen here, not in a `releaseAfterScope` finalizer (Ox runs those after the join). Idempotent; default no-op."* `Agent`: `private[orca] def close(): Unit = ()`; `BaseAgent`: `override private[orca] def close(): Unit = backend.close()`. `DefaultFlowContext.close()`: closes all five agents best-effort:

```scala
  /** Tear down context-owned background resources by closing every agent
    * (each delegates to its backend; all default to no-op — today only
    * opencode holds a live resource, the shared `serve` process). Runs in the
    * flow body's `finally`, before the flow scope joins its forks (see
    * AgentBackend.close). Per-agent best-effort: one failing close must not
    * keep the others (or the interaction) from closing.
    */
  def close(): Unit =
    List(claude, codex, opencode, pi, gemini).foreach: a =>
      try a.close()
      catch case NonFatal(e) => log.debug("agent close failed", e)
```

(add the `LoggerFactory` logger the class currently lacks; note: this now also calls `close()` on CALLER-SUPPLIED agents — with the no-op default that is behavior-preserving, and an override that does close is presumably what the caller wants; document this in `withDefaults`' scaladoc, replacing the "caller-supplied opencode owns its own lifecycle" note).

- [ ] **Step 1: Failing test** — in `tools` (co-locate with `BaseAgentTest.scala`): `"close() delegates to the backend"` — stub backend records `close()`; `agent.close()` → recorded once. RED: no `close` member.
- [ ] **Step 2: Implement** the SPI methods; opencode: `override def close(): Unit = server.close()` (delete `shutdown()`; update its scaladoc; grep `shutdown` for stale references in opencode + runner).
- [ ] **Step 3: DefaultFlowContext** — delete the `closeHook` constructor param and the `(opencodeAgent, opencodeClose)` tuple in `withDefaults` (the opencode arm becomes a plain `wiring.opencode.getOrElse(new DefaultOpencodeAgent(OpencodeBackend(OsProcCliRunner, workDir, wiring.opencodeLauncher), ...))`); implement close-all as above.
- [ ] **Step 4: runFlow** — replace `var closeContext` with structural nesting:

```scala
  supervised:
    val effectiveInteraction = interaction.getOrElse(
      TerminalInteraction.start(workDir = Some(workDir))
    )
    try
      val dispatcher = ...
      ...
      val ctx = DefaultFlowContext.withDefaults(...)
      try
        val setup = FlowLifecycle.setup(...)
        FlowLifecycle.rehydrateSessions(ctx, ctx.agent, store)
        try body(using ctx)
        catch ...
        FlowLifecycle.teardownSuccess(...)
      finally
        // Before the scope joins its forks: closing the context destroys the
        // opencode serve process so its drain forks' reads EOF and the join
        // can't hang (Ox runs releaseAfterScope only after the join).
        ctx.close()
    finally effectiveInteraction.close()
```

The `var` and its initial-no-op window disappear; construction failures before `ctx` exists skip `ctx.close()` structurally (nothing to close — backends spawn nothing at construction; note this in the comment).
- [ ] **Step 5: Verify** full gate — `OpencodeFlowTest` (serve teardown) is the load-bearing regression net. **Step 6: Commit** — `refactor(backend)!: uniform close() on the agent SPI; drop closeHook and the closeContext var`.

---

### Task 5: `FlowLifecycle.run` — one owner for the phase protocol (finding 3.4)

**Files:**
- Modify: `runner/src/main/scala/orca/runner/FlowLifecycle.scala` (new `run`), `runner/src/main/scala/orca/flow.scala` (`runFlow` shrinks to construction + delegation)
- Test: `runner/src/test/scala/orca/runner/FlowLifecycleTest.scala` (green with at most import-level changes — the `runFlow` seam it drives keeps its signature)

**Interfaces:**
- Produces, in `FlowLifecycle`:

```scala
  /** The complete phase protocol for one run, in its mandated order: setup
    * (branch + log binding) → session rehydration → body → disjoint
    * success/failure teardown. Extracted here so the ordering invariants that
    * used to live as comments in the runner's entry point have one executable
    * owner (ADR 0018 §2.4/§2.5). The context must be fully constructed (setup
    * resolves the leading agent for branch naming and reads `ctx.git`).
    *
    * Failure path: emits the error (unless the exception already reported
    * itself), logs, runs `teardownFailure`, rethrows. Success path runs
    * `teardownSuccess`. The two are structurally disjoint — the catch
    * rethrows, so success teardown is unreachable on failure.
    */
  private[orca] def run[B <: BackendTag](
      args: OrcaArgs,
      ctx: DefaultFlowContext[B],
      branchNaming: Option[BranchNamingStrategy],
      store: ProgressStore,
      returnToStartBranch: Boolean,
      debug: Boolean
  )(body: FlowControl ?=> Unit): Unit =
    val log = LoggerFactory.getLogger("orca.flow")
    val setup = FlowLifecycle.setup(args, ctx.agent, ctx.git, branchNaming, store)
    rehydrateSessions(ctx, ctx.agent, store)
    try body(using ctx)
    catch
      case NonFatal(e) =>
        val alreadyEmitted = e match
          case fe: OrcaFlowException => fe.alreadyEmitted
          case _                     => false
        if !alreadyEmitted then ctx.emit(OrcaEvent.Error(throwableMessage(e)))
        log.debug("flow aborted", e)
        if debug then e.printStackTrace(System.err)
        teardownFailure(ctx.git)
        throw e
    teardownSuccess(ctx.git, setup, returnToStartBranch)
```

(move the load-bearing comments — top-level-stage error reporting, teardown disjointness — with the code; `throwableMessage` currently lives in flow.scala/runner — locate it (`grep -rn "def throwableMessage" runner`) and move or reference it so both sites compile.)
- `runFlow` keeps its exact signature (the test seam) and shrinks to: supervised scope, interaction, dispatcher, store, ctx construction, then `try FlowLifecycle.run(args, ctx, branchNaming, store, returnToStartBranch, debug)(body) finally ctx.close()` inside the interaction finally — the phase ordering comments move out with the code; what remains documents only construction order (store/context before `run`, because setup needs the resolved agent + git).

- [ ] **Step 1:** Move the code per the interface block (this is an extraction — behavior identical; FlowLifecycleTest is the net).
- [ ] **Step 2: Verify** full gate; FlowLifecycleTest green without assertion changes.
- [ ] **Step 3: Commit** — `refactor(runner): FlowLifecycle.run owns the setup→rehydrate→body→teardown protocol`.

---

### Task 6: Docs (finding 3.5) + tracker + review wave

- [ ] **Step 1: Document the nested-stage commit sweep.** (a) `flow/src/main/scala/orca/Flow.scala` — on `recordAndCommit` (or the `stage` scaladoc, whichever documents commit behavior; find the `add -A` site): add: *"A nested stage's commit stages the whole tree (`add -A`), so it sweeps up any uncommitted edits the OUTER stage's body made before the nesting point. If the flow later fails, the outer stage re-runs against a tree already containing its own partial work (committed under the inner stage's message) — resume is only correct if the outer body is idempotent over its own leftovers. Prefer not editing files around a nested stage; do the edits inside their own stage."* (b) `adr/0018-stage-bound-flow-runtime.md` — dated amendment note (2026-07-06) next to the §2.1 nesting note with the same content, marked as an authoring rule.
- [ ] **Step 2: Tracker.** `complexity-review.md`: tick 3.1–3.6 with the fixing commits (3.1→Task 4's, 3.2→Task 3's, 3.3→Task 1's, 3.4→Task 5's, 3.5→this docs commit "session-identity-fixes docs", 3.6→Task 2's). Also update AGENTS.md if it describes the old closeHook/shutdown wiring (grep `closeHook`, `shutdown`, `closeContext`). Commit — `docs: nested-stage commit sweep authoring rule; tick complexity findings 3.1-3.6`.
- [ ] **Step 3: Review wave.** `review-package` over the whole 3.x range; dispatch in parallel: **code-functionality-reviewer** (most capable model — shutdown ordering/hang-freedom is the core risk: probe "can the join hang in any construction-failure or mid-body-cancel scenario, before and after"), **scala-fp-reviewer** (Ox scope/resource ownership, the new close() capability threading), **simplicity-reviewer** (did the wiring/lifecycle collapse leave dead params or new indirection), **test-reviewer** (FlowLifecycleTest/OpencodeFlowTest adequacy for the new shapes; close-delegation and never-spawn-probe pins).
- [ ] **Step 4:** Triage; ONE batch fixer for accepted findings; re-verify; commit — `fix(runner): address review findings on the lifecycle refactor`. Push the branch.
