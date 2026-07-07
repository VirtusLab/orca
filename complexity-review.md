# Complexity review — findings tracker

Conducted 2026-07-06 on branch `stage-bound-flow-runtime`. Five parallel reviews
(tools, flow, runner, backends, cross-cutting architecture), synthesized and
deduplicated. Mark items `[x]` as they are resolved; add a short note (commit /
decision) next to done items.

Overall verdict: file-level code is clean and well-commented; the dominant
complexity is **protocolar** — multi-party invariants living in prose (doc
comments, ADRs) rather than types, several with shipped-bug history.

---

## 1. Session identity & resume (HIGH — flagged independently by 3 of 5 reviewers)

- [x] **1.1 Split client vs wire session ids.** (done: 4835328) One type `SessionId[B]` serves two
  identities; codex/gemini/pi must remember `.copy(sessionId = session)` per call
  site, claude deliberately doesn't, `DefaultAgentCall.runInteractiveOnce` corrects
  a third way. Pi's copy is a cargo-culted no-op. Split into
  `ClientSessionId`/`WireSessionId` (or at least move the re-stamp uniformly into
  `drainAndCommit`).
  Refs: `tools/.../agents/BackendTag.scala:29-54`, `tools/.../backend/Conversations.scala:110-149`,
  `tools/.../agents/AgentCall.scala:246-266`, `pi/.../PiConversation.scala:169`.

- [x] **1.2 Make resume durability a single structural choice on the SPI.** (done: f3fb914) Resume
  requires aligning registry shape + `commitSuccess` timing, `resumeWireId`
  override, `registerSession` override, `persistResumeWireId`, `rehydrateSessions`
  — six sites, three modules; three links are silent-no-op defaults. Claude and
  codex each already shipped a resume bug here (per AGENTS.md). Sketch:
  `AgentBackend.sessions: Durable(registry, probe, wireId) | Ephemeral`, deriving
  the rest as final methods.
  Refs: `tools/.../backend/AgentBackend.scala:84-124`, `tools/.../agents/Agent.scala:133-154`,
  `flow/.../Session.scala:93-108`, `runner/.../FlowLifecycle.scala:28-40`.

- [x] **1.3 Tag `SessionRecord` with its backend; fix lead-only rehydration.** (done: 2b04e09)
  Records are untagged, so rehydration replays *every* record into the *lead*
  agent's registry via unchecked `SessionId[B](record.id)`; a non-lead
  `claude.session(seed)` in a codex-led flow wedges deterministically on resume
  ("known limitation" comment only).
  Refs: `flow/.../progress/ProgressLog.scala:50-55`, `runner/.../FlowLifecycle.scala:38-40`,
  `runner/.../flow.scala:258-263`.

- [x] **1.4 Give sessions name-based identity.** (done: 100853b) Session resume keys are purely
  positional (call ordinal); reordering or making a `session(...)` call conditional
  silently re-keys later sessions, with only an after-the-fact runtime warning.
  Stages have `name#occurrence`; sessions should too.
  Refs: `flow/.../Session.scala:30-56`, `flow/.../FlowControl.scala:41-46`.

- [x] **1.5 Document/fix session-record durability vs `git reset --hard`.** (done: session-identity-fixes docs commit)
  `upsertSession` writes without committing; failure teardown resets hard, so the
  "retry reuses the session" contract only holds if a stage commit intervened.
  Either commit the log delta in `session(...)` or document at `upsertSession`.
  Refs: `flow/.../progress/ProgressStore.scala:34-41`, `flow/.../Session.scala:15-24`.

- [x] **1.6 Fix the stale `commitSuccess` timing comment.** (done: 4835328) `SessionRegistry.scala:24-25`
  says "claude at spawn, codex post-drain"; reality is mode-dependent (claude
  commits post-drain autonomous, at-spawn interactive).
  Refs: `tools/.../backend/SessionRegistry.scala:16-45`.

- [x] **1.7 Collapse the session-protocol forwarding trio.** (done: f3fb914) `sessionExists` /
  `resumeWireId` / `registerResumeWireId` exist identically on `Agent`,
  `AgentBackend`, `SessionRegistry` with `BaseAgent` as pure forwarding — the only
  pure-forwarding layer in the agent-call stack. (Largely falls out of 1.2.)
  Refs: `tools/.../agents/BaseAgent.scala:69-91`.

## 2. Cross-backend divergence (HIGH)

- [x] **2.1 Unify/surface `autoApprove` + `ToolSet` enforcement semantics.** (done: 178351a) The same
  typed knob means: real allowlist (claude; but `Only(empty)` still widens to
  `acceptEdits`), sandbox approximation dropped on resume (codex), widened to yolo
  (gemini), not encoded at all — depends on server permission default (opencode),
  ignored (pi). "No-edit" `ToolSet` is hard on three backends, prompt-only on codex
  `NetworkOnly` and pi. Add a per-backend `EnforcementLevel`
  (Hard/SandboxApprox/PromptOnly/Ignored) surfaced on the SPI + one shared test
  table; comment or fix the claude `Only(empty)` widening.
  Refs: `claude/.../ClaudeArgs.scala:88-115`, `codex/.../CodexArgs.scala:83-90,135-161`,
  `gemini/.../GeminiArgs.scala:81-94`, `opencode/.../OpencodeArgs.scala:74-93`,
  `pi/.../PiArgs.scala:20-65`.

- [x] **2.2 Hoist the autonomous drain/commit shell into `Conversations`/`AgentBackend`.** (done: 53d36f9 — shared shell hoist; opencode divergence was absorbed earlier by 4835328, see the existing note)
  Four backends repeat `supervised → open → drainAndCommit → finally cancel`;
  opencode inlines a divergent copy that skips the error rewrap (no comment why),
  so the next `drainAndCommit` fix silently misses it.
  Refs: `claude/.../ClaudeBackend.scala:98-125`, `codex/.../CodexBackend.scala:76-109`,
  `gemini/.../GeminiBackend.scala:55-84`, `pi/.../PiBackend.scala:47-71`,
  `opencode/.../OpencodeBackend.scala:85-114`, `tools/.../backend/Conversations.scala:110-149`.
  Note: the opencode inline-drain divergence (skipped error rewrap) was absorbed
  into `Conversations.drainAndCommit` by 1.1's task (4835328) — opencode now uses
  the shared helper too. The shared-shell hoist itself (the repeated
  `supervised → open → drainAndCommit → finally cancel` across all five backends)
  remains open.

- [x] **2.3 Pin the `ConversationEvent` grammar.** (done: a74a4cc) Turn-end-on-failure and
  `ToolResult.toolName` behaviour differ across all five drivers; consumers survive
  via defensive flushes. Document the sequence contract on `ConversationEvent` and
  add one parameterized conformance test.
  Refs: `gemini/.../GeminiConversation.scala:113-115`, `opencode/.../OpencodeConversation.scala:143-152`,
  `pi/.../PiConversation.scala:165-167`, `codex/.../CodexConversation.scala:165-169`,
  `claude/.../ClaudeConversation.scala:158-165`.

- [x] **2.4 De-duplicate the ask_user 60s-timeout workaround; clarify gemini.** (done: f0e582c) Same
  fix implemented twice in different units/encodings (millis in hand-built JSON for
  claude, seconds in TOML for codex); gemini sets no timeout — immune or forgotten?
  Hoist one constant with per-backend renderers; state the answer in `GeminiSettings`.
  Refs: `claude/.../ClaudeBackend.scala:261-269`, `codex/.../CodexArgs.scala:96-110`.

- [x] **2.5 Move the pi ask_user extension out of a Scala string literal.** (done: e87c207) Untyped,
  unhighlighted TypeScript embedded in Scala; move to a classpath resource.
  Refs: `pi/.../PiAskUserExtension.scala:36-70`.

## 3. Shutdown & lifecycle choreography (HIGH)

- [x] **3.1 Localize backend shutdown ownership.** (done: 98da368) `runFlow` holds a reassigned
  `var closeContext: () => Unit` invoked in a body-level `finally` in mandated
  order, existing solely for opencode's drain forks; `opencodeLauncher` leaks into
  the public `flow(...)` signature and `DefaultFlowContext` grew a one-backend
  `closeHook`. Give agents a uniform `close()` (no-op default); backend-specific
  Ox-ordering knowledge collapses into the backend.
  Refs: `runner/.../flow.scala:94,197-299`, `runner/.../DefaultFlowContext.scala:54-65,129-144`.

- [x] **3.2 Simplify opencode once-init/shutdown.** (done: 519ab81) `serverRef` AtomicReference +
  `firstWorkDir` CAS side-channel smuggling an argument into a `lazy val` + second
  load-bearing lazy + `stopped` re-check race close — for a cold path that runs
  once. workDir is already assumed constant: construct eagerly, use `synchronized`.
  Refs: `opencode/.../OpencodeBackend.scala:43-83,168-173`, `opencode/.../OpencodeServer.scala:22-57,133-141`.

- [x] **3.3 Flatten `flow()`'s exit path; delete `bodySucceeded`.** (done: b197dce) Cleanup duplicated
  on two routes because `System.exit(1)` skips the outer finally; `bodySucceeded`
  is dead weight (the catch rethrows). Set a `failed` flag, exit after the finally.
  Refs: `runner/.../flow.scala:119-158,276-293`.

- [x] **3.4 Give the phase-ordering protocol a single owner.** (done: d1e71cd) ~7 cross-module
  ordering invariants (setup ordering, snapshot-before-stash, diff-before-append,
  emits-outside-try, closeContext-before-interaction-close-before-join, disjoint
  teardowns) enforced only by prose. Move the bracketing wholly into
  `FlowLifecycle.run(wiring)(body)`; consider encoding the most fragile orderings
  as types.
  Refs: `runner/.../flow.scala:200-299`, `flow/.../Flow.scala:55-58,130-141`,
  `runner/.../FlowLifecycle.scala:88-92`.

- [x] **3.5 Document the nested-stage commit sweep.** (done: session-identity-fixes docs commit) Inner-stage commits use
  `add -A`, sweeping the outer stage's uncommitted edits under the inner commit;
  on failure the outer stage re-runs against its own leftovers. Documented nowhere
  (ADR 0018 §2.1 nesting note doesn't cover it). Document in the ADR authoring
  rules or forbid interleaved edits around nested stages.
  Refs: `flow/.../Flow.scala:28-29,122-145`.

- [x] **3.6 Collapse the 17-parameter wiring tunnel.** (done: 611f5e3) ~15 override knobs spelled out
  four times (`flow` → private mirror → `withDefaults` → ctor); duplicated
  now-unreachable git default in `withDefaults` can silently drift. Introduce a
  `FlowWiring` case class; delete the dead default.
  Refs: `runner/.../flow.scala:82-102,172-191,216`, `runner/.../DefaultFlowContext.scala:41-55,109-126,198`.

## 4. Review-and-fix loop (HIGH within flow)

- [x] **4.1 Restructure `reviewAndFixLoop`.** (done: 0b09312) 230-line closure forest: 11 params,
  nested generic method, local enum, 3-tuple-returning helper, stop policy in a
  separately-defined generic driver (`fixLoopWithState`, whose `S` threading serves
  two callers, one `Unit`). State split across threaded state, closure captures,
  and driver accumulator. Promote to a small class with one plain `@tailrec` loop;
  inline the driver; keep `fixLoop` as a thin recursion.
  Refs: `flow/.../review/ReviewLoop.scala:28-107,201-428`.

- [x] **4.2 Replace stringly reviewer identity with structured refs.** (done: 394cc6f) `Agent.name`
  doubles as identity and cost prefix (`"reviewer: "`), forcing strip/re-match/warn
  across three files (one bug already shipped); the `.as[RB]` session retag's
  soundness rests on roster uniqueness while agents actually come from an
  unconstrained user selector — wrong-backend session delivery is representable.
  Selection should return roster-resolved refs; apply the cost prefix only at the
  event-emission edge; key the session map by roster entry.
  Refs: `flow/.../review/Reviewers.scala:31-45,101-145`, `flow/.../review/ReviewerSelector.scala:20-25,63-65,86-150`,
  `flow/.../review/SelectedReviewers.scala:11-14`, `flow/.../review/ReviewLoop.scala:176-184,239-243,270-287`.

- [x] **4.3 Remove `agentDriven`'s captured `InStage` + `var cached`.** (done: ad1a366) Institutionalises
  the smuggled-token pattern ADR 0018 §5 warns about, plus a single-loop-only cache
  contract enforced by nothing. Hoist the one-time pick into `reviewAndFixLoop`
  from a pure selector spec.
  Refs: `flow/.../review/ReviewerSelector.scala:55-59,78-86`.

## 5. Exception-based control-flow protocols (MEDIUM)

- [x] **5.1 Single-home retry classification.** (done: 943e9b1 + ac3f180) Retryability = which exception class
  survives a four-file relay (`awaitResult` blanket-wraps into non-retryable;
  `drainAndCommit` launders others back to retryable; `DefaultAgentCall` checks
  `isInstanceOf`; parse-retry state rides a captured `var lastFailure`). The
  blanket wrap makes the rewrap branch near-dead and claude backend comments
  describe retries that can never happen. Make retryability data (returned ADT),
  or single-home the classification and fix the comments.
  Refs: `tools/.../agents/AgentCall.scala:155-214`, `tools/.../backend/ForkedConversation.scala:144-155`,
  `tools/.../backend/Conversations.scala:135-149`, `claude/.../ClaudeBackend.scala:119-125,213-219`.

- [x] **5.2 Replace `alreadyEmitted` var with a type or identity-set.** (done: b494133) Exactly-once
  error reporting via a mutable field on the in-flight exception, cooperating
  across three modules; only `OrcaFlowException` participates, so a plain
  `RuntimeException` unwinding nested stages is reported twice. Wrapper type
  (`ReportedFlowException`) or identity-set at the reporting layer.
  Refs: `tools/.../OrcaFlowException.scala:14-18`, `flow/.../Flow.scala:85-113,206-208`,
  `runner/.../flow.scala:283-287`.

- [x] **5.3 Make `emit` total at the dispatcher.** (done: d87fde5) Listener exceptions propagate into
  stage control flow by design; every emit site is ordered defensively (one
  near-miss already documented at `Flow.scala:55-58`), and `extraListeners` is
  public API on fork threads. Catch per-listener, log loudly, optionally
  quarantine; delete the ordering caveats.
  Refs: `flow/.../events/EventDispatcher.scala:3-18`, `tools/.../events/OrcaEvent.scala:70-77`.

## 6. Capability & type-level machinery (MEDIUM)

- [x] **6.1 Resolve the `LeadB` halfway point.** (done: this docs commit — lean-in: helper-authoring guidance; tags kept) Path-dependent `SessionId[ctx.LeadB]`
  stops unifying at the first helper boundary (worst mistake-to-diagnostic distance
  in the DSL); the library itself bypasses it (explicit `[B]` params +
  `SessionId.Untyped` + unchecked `.as[RB]`; rehydration mints from untagged
  strings). Either lean in (ship `Sessioned[B]` as the documented helper currency)
  or lean out (untag `SessionId`, delete `LeadB`). Current state pays for both.
  Refs: `flow/.../FlowContext.scala:34-50`, `flow/.../accessors.scala:30-54`,
  `tools/.../agents/BackendTag.scala:46-54`, `flow/.../review/ReviewLoop.scala:173-186,280-299`.

- [x] **6.2 Freeze `InStage.unsafe` mint sites; fix the capture-checking plan.** (done: 702ae13)
  Seven mints across three modules, each self-authorising; funnel through one
  named constructor so auditing is one grep. ADR 0018 §6's capture-checking
  endgame would outlaw the reviewer fan-out's load-bearing token capture —
  amend to distinguish index-mutating capabilities (must not cross forks) from
  LLM-call capabilities (must). Extend the `implicitNotFound` with the
  helper-author clause ("thread `(using InStage)` upward").
  Refs: `tools/.../InStage.scala:22-36`, mints: `flow/.../Flow.scala:80,132`,
  `flow/.../Session.scala:52,105`, `runner/.../FlowLifecycle.scala:86,178,226`;
  `flow/.../review/ReviewLoop.scala:334-356`; `adr/0018-...md` §6.

- [x] **6.3 Kill the `AgentConfig.default` `eq` sentinel.** (done: 442e577) Merge-vs-replace decided
  by reference identity; `AgentConfig()` at a call site silently wipes the tool's
  model/system-prompt/toolset. Use `Option[AgentConfig]` or
  `configure: AgentConfig => AgentConfig = identity`.
  Refs: `tools/.../agents/AgentConfig.scala:62-71`, `tools/.../agents/BaseAgent.scala:126-132`.

- [x] **6.4 De-trick `ForkedConversation` internals (keep the design).** (done: 9abe577 + a2ef55e — settledOutcome de-atomized earlier; Peek enum; lazy-fork design kept, documented) Two-phase
  factory (`ForkedConversation.start(...)`) to remove the lazy-fork /
  subclass-initializer-race reasoning; fold `settledOutcome` into the reader's
  return path (same-thread anyway); replace `peeked: Option = null` tri-state with
  an explicit small enum/flag. Core design (channels, outcome precedence, hooks)
  is judged justified — don't rewrite it.
  Refs: `tools/.../backend/ForkedConversation.scala:27-35,84-94,124-136,251-290,339-355`.

- [x] **6.5 Name the `JsonData` primitive givens; scope the bridge givens.** (done: ffc656e) Implicit
  cycle currently dodged via a "don't summon" comment and a reference to the
  compiler-synthesized name `given_JsonData_String` — breaks silently if the
  anonymous given is renamed/reordered.
  Refs: `tools/.../agents/JsonData.scala:67-71,134-137`, `tools/.../agents/BackendTag.scala:57-66`.

## 7. Concrete latent bugs (fix independently of any restructuring)

- [x] **7.1 `JLinePrompter` lifecycle.** (done: 15dac1b + bd3d15e) Per-conversation `finally closePrompter()`
  closes the process-global singleton: first conversation force-allocates the lazy
  terminal just to close it; the second interactive prompt of any run operates on
  closed I/O. Make the prompter per-renderer, or close only at `Interaction.close()`
  guarded by an initialized flag.
  Refs: `runner/.../terminal/ConversationRenderer.scala:78-88,244-246,316-329`.

- [ ] **7.2 `teardownSuccess` swallow gap.** Doc says remove/commit/handoff errors are
  cosmetic and swallowed; code swallows only `NoSuchFileException` on the remove —
  any other IO error ends a *successful* run with `exit(1)` and no terminal error.
  Flatten to one `bestEffort` helper with uniform policy + debug logging.
  Refs: `runner/.../FlowLifecycle.scala:161-193`, consequence via `runner/.../flow.scala:150-153,292-293`.

- [x] **7.3 Codex schema file.** (done: dd5015e) Fixed path `workDir/.codex/orca-output-schema.json`:
  races under the parallel reviewer fan-out (concurrent structured calls, one
  file), never cleaned up, swept into flow commits by `add -A`. Use `os.temp` or a
  session-suffixed path + delete-on-finalize (claude already does both for MCP
  config).
  Refs: `codex/.../CodexBackend.scala:214-221`, contrast `claude/.../ClaudeBackend.scala:243-250`.

- [x] **7.4 Refuse to commit empty session ids.** (done: dd5015e) Defensive `getOrElse("")` parsing +
  unconditional commit means a missing init event yields registry entry `""` and a
  later `codex exec resume ""`. Guard at the settle point or centrally in
  `drainAndCommit` (refuse empty/unsafe `result.sessionId`).
  Refs: `codex/.../CodexConversation.scala:54,212-219`, `gemini/.../GeminiConversation.scala:50,138-145`,
  `tools/.../backend/Conversations.scala:148`.

- [x] **7.5 Claude `sessionExists` probes the wrong directory when `workDir ≠ pwd`.** (done: dd5015e)
  Probe slugs construction-time `os.pwd`; spawns use per-call `workDir` — resume
  silently always re-seeds in worktree flows. Add `workDir` to `sessionExists`, or
  record spawn workDir per session; at minimum log the mismatch.
  Refs: `claude/.../ClaudeBackend.scala:43-69,228,287-294`.

- [x] **7.6 Structured-result rendering: code vs ADR 0008.** (done: e9d751d) Renderer drops streamed
  JSON in structured mode; listener renders only when `Announce` exists — so
  `resultAs[O]` without an `Announce` given produces no terminal output for the
  agent's answer. ADR specifies a raw-text `●` fallback. Decide the behaviour,
  make one component own the decision, update the ADR.
  Refs: `runner/.../terminal/ConversationRenderer.scala:43-50,151-166`,
  `runner/.../terminal/TerminalEventListener.scala:74-84`, `adr/0008-terminal-output-design.md`.

- [x] **7.7 `formatCommand` stderr leak.** (done: 0b09312) Bare `os.proc("bash","-c",cmd).call(check=false)`
  inherits stderr (can tear the status row), violating the project's own QuietProc
  rule; sibling `lint` 80 lines away is compliant; adjacent comment is wrong about
  capture. Route through `QuietProc.call` / `mergeErrIntoOut = true`; fix comment.
  Refs: `flow/.../review/ReviewLoop.scala:382-385,468-471`.

- [x] **7.8 Override params are event-blind; default agents unsubstitutable.** (done: 5be491a)
  User-supplied `claude = Some(...)`/`git = Some(...)` can't reach the dispatcher
  created later inside `runFlow` — costs/steps silently vanish from tracker and
  terminal. `DefaultClaudeAgent`/`ClaudeBackend` are `private[orca]`, so the
  documented "substitute a configured claude" means reimplementing the trait.
  Make overrides factories receiving the wiring (mirroring the selector pattern),
  or publicize default agent constructors and document the limitation.
  Refs: `runner/.../flow.scala:82-101,207-216`, `claude/.../DefaultClaudeAgent.scala:19`,
  `runner/.../DefaultFlowContext.scala:141-201`.

- [x] **7.9 Empty-`Announce` silent turn + `deltasSinceTurnBoundary` overload.** (done: a2ef55e) One
  claude-driver flag carries two unrelated meanings (partials-fallback gating and
  error-display heuristic), coherent only via a wire-ordering invariant documented
  elsewhere. Split into two named flags or drop heuristic (b).
  Refs: `claude/.../ClaudeConversation.scala:52-57,101-143,194-201`.

## 8. Hygiene (small, mechanical)

- [ ] **8.1 Delete vestigial `(using BufferCapacity)`** on claude/codex/gemini backend
  constructors (used nowhere; opencode/pi already dropped it).
  Refs: `claude/.../ClaudeBackend.scala:19,45`, `codex/.../CodexBackend.scala:19,44`,
  `gemini/.../GeminiBackend.scala:20,45`.
- [x] **8.2 Delete dead `ReviewContext`** (done: this docs commit — ReviewContext deleted) (zero usages in main, tests, exports, examples).
  Refs: `flow/.../review/ReviewContext.scala`.
- [ ] **8.3 Remove or relocate `Task.completed`/`markComplete`/`firstIncomplete`** —
  checkbox state retired by ADR 0018; nothing in the library sets `completed`.
  Refs: `flow/.../plan/Task.scala:15-20`, `flow/.../plan/Plan.scala:50-56,264-274`.
- [ ] **8.4 Unify the two quiet-subprocess stacks; fix the `QuietProc` doc.** Doc
  claims `CliRunner` wraps `QuietProc` — it doesn't; the stacks already diverged on
  `nonInteractiveEnv` (`OsGitHubTool.currentBranchGit` runs git without
  hang-protection). Delegate one to the other; route git-in-GitHubTool through the
  shared helper.
  Refs: `tools/.../subprocess/QuietProc.scala:13-15`, `tools/.../subprocess/OsProcCliRunner.scala:19-43`,
  `tools/.../tools/GitHubTool.scala:275-284`, `tools/.../tools/GitTool.scala:478-483`.
- [ ] **8.5 Localize `Conversations.drainAutonomous`'s withheld-turn state machine.**
  Flush semantics split across `closeTurn`/loop/`finally`; mid-turn crash silently
  drops a partial turn; structured mode delays every turn by one on screen
  (unstated). Emit eagerly and defer exactly one pending emission, or suppress at
  the listener.
  Refs: `tools/.../backend/Conversations.scala:31-103`.
- [x] **8.6 Simplify indent plumbing in the terminal listener.** (done: concurrency-audit commit, session-identity-fixes) Four coordinated
  mutable artifacts (`StageDepth`, `StageStack`, lock, `@volatile` snapshot) for
  one value; desync possible after a stray pop; re-indent transform duplicated
  char-for-char in two files; class doc misstates the concurrency model (dispatcher
  is sequential — the hazard is concurrent emitters). One `@volatile var stack: List[String]`
  + shared `indentBlock` helper; fix the comment.
  Refs: `runner/.../terminal/TerminalEventListener.scala:8-13,31-61,109-129,170-182`,
  `runner/.../terminal/StageDepth.scala`, `runner/.../terminal/ConversationRenderer.scala:184-186`.
- [ ] **8.7 Surface `ProgressStore.load` corruption as data.** Unparseable log ⇒
  silent fresh run (second branch, re-run stages); `Absent | Corrupt(reason) | Loaded`
  would make the third arm explicit and warnable. Consider caching the parsed log
  and temp-file-rename writes while there.
  Refs: `flow/.../progress/ProgressStore.scala:69-71,92-119`, `runner/.../FlowLifecycle.scala:93-141`.
- [ ] **8.8 Misc small items.** `OpencodeConversation`'s fourth lifecycle flag
  (expose `isSettled` on the base instead) — `opencode/.../OpencodeConversation.scala:73-86,143-156`;
  pi failure-path cleanup depending on a by-name param in another module
  (allocate the system-prompt file before `open`) — `pi/.../PiBackend.scala:117-131`;
  `withCheapModel`/`withSelfManagedGit` silent no-op builder defaults —
  `tools/.../agents/Agent.scala:91,126`; `isSafeSessionId` placement —
  `tools/.../agents/BackendTag.scala:26-27`; move the embedded git credential
  helper toward a `GitPushAuth` extraction if it grows — `tools/.../tools/GitTool.scala:559-602`.

---

## Reviewed and judged fine (no action; listed to prevent re-litigating)

`ForkedConversation`'s core design (channels, three forks, outcome precedence —
irreducible concurrency, well-contained; see 6.4 for the internal de-tricks);
`AskUserBridge`; `OpencodeServer.start()`'s body (each step counters a verified
deadlock); `GeminiSettings` merge/restore; `TerminalOutputState` behind the actor;
`OrcaEvent`/`ConversationEvent` dual vocabulary; the `Plan` grid + flat wire DTOs;
`RecoveryCheck` paranoia; the ~7-layer agent-call stack (each layer non-forwarding
except the trio in 1.7); `FlowContext { type LeadB }` as a type member vs
parameterized context (the *right* call — 6.1 is about the halfway enforcement);
`FlowControl <: FlowContext` downgrade; `@implicitNotFound` diagnostics + negative
compile tests; two-list `flow(...)` shape and `import orca.{*, given}` ceremony;
`InStage` as a script-surface guard-rail (6.2 is about mint hygiene, not the
concept); `slug` shared producer/validator; `CostTracker` atomic state;
`ProgressStore` single-writer rationale; centralized stderr matchers in
`OsGitTool`/`OsGitHubTool`; `ToolInputSummary`'s hand-rolled extractor; `OrcaLog`
logback surgery (documented, reverted); pi `stdinLock`; codex resume flag
subsetting (CLI-imposed); `AskUserEchoes` per-backend matchers.
