# Complexity review, second pass — findings tracker

Conducted 2026-07-07 on branch `session-identity-fixes` (i.e. *after* the fixes
from [complexity-review.md](complexity-review.md) landed). Six parallel reviews
(session core, flow, backends, runner, tools/utilities, cross-cutting
architecture), synthesized and deduplicated. Mark items `[x]` as they are
resolved; add a short note (commit / decision) next to done items.

Overall verdict: the first-pass fixes held — every structural change was
verified in place and none was found to have merely moved its problem. The
recurring **new** pattern: several fixes built a single funnel for one path
while leaving an equally-typed sibling door open (resume-but-not-fresh,
read-but-not-write, body-but-not-setup). Epics 1 and 2 were each flagged
independently by two of six reviewers.

Work top to bottom: High → Medium → Low — except Epic 0, which shapes the
signatures Epics 2, 5, and 7 introduce and therefore precedes them. Epics 1,
3, 4, 6, 8, 9 are capture-checking-neutral and can proceed in parallel with
Epic 0.

**Standing constraint (owner decision, 2026-07-07): capture checking lands in
the near term** to enforce the concurrency and `InStage`-capture guarantees
ADR 0018 §6 defers. Consumer compatibility is a non-issue: flows are consumed
through scala-cli scripts whose compiler version we control (always latest),
and CC annotations are ignored by compilers without the feature enabled.
Scala 3.8's capture checker + separation checking
(`caps.SharedCapability` vs exclusive capabilities) map directly onto the
ADR's fork-crossing vs fork-opaque split. Consequences threaded through this
tracker: Epic 0 is the enabling work; 7.1 and 7.5 are deliberate *bridges*
(runtime backstops for what CC doesn't or can't check); 2.1 and 11.1 choose
signatures so CC strengthens them for free. CC is evolving fast (e.g. the
universal capability was recently respelled `cap` → `any`) — re-read the
current reference docs at implementation time, not from memory:
<https://docs.scala-lang.org/scala3/reference/experimental/cc.html> and
<https://nightly.scala-lang.org/docs/reference/experimental/capture-checking/separation-checking.html>.

---

# HIGH priority

## Epic 0 — Capability model & capture-checking readiness

`InStage` today authorizes two things at once: index-like mutations (git/fs/gh
writes, progress-store writes — must NOT cross a fork boundary) and LLM-call
gating (MUST cross — the reviewer fan-out's `InStage` capture is load-bearing).
ADR 0018 §6's amendment records that this split must be designed before capture
checking lands; with CC now near-term, that design is due. One hard
prerequisite verified in source: the token's `opaque type InStage = Unit`
representation is untrackable by CC (a `Unit` value is pure — there is no
reference to track). Scope decision (owner, 2026-07-07): the sharable gate
covers **any** LLM call regardless of toolset — no toolset-aware typing. Types
can't reach what the agent subprocess does (that enforcement is
`AgentConfig.tools` + the `Enforcement` matrix); the exclusive token guards
only the Scala-side index-like writes, and agent interference with `.orca`
progress files is out of the threat model (adversarial agents are not
considered; agents are trusted-but-fallible).
Refs: `tools/src/main/scala/orca/InStage.scala:30`,
`flow/src/main/scala/orca/FlowControl.scala:15-16`,
`adr/0018-stage-bound-flow-runtime.md:780-802`,
`flow/.../review/ReviewLoop.scala` (fan-out capture).

- [x] 0.1a (done: 0e361ae) Toolchain bump, standalone (amended 2026-07-07 after research —
  the build is on Scala **3.3.6 LTS**, not 3.8.x, so this is an LTS→latest
  migration, not a pin): Scala **3.8.4** (avoid 3.8.0/3.8.1 — published
  runtime regressions; 3.9.0-RC1 is RC), jsoniter **2.38.17** (3.7+-specific
  derivation fix), tapir → 1.13.25, Ox → 1.0.5; fix new warnings
  (`-Wunused` additions, `using`-required-for-explicit-implicit-args, 3.7
  givens-prioritization flips — `-source:3.7-migration -rewrite` available);
  full test run; **zero CC content** in this commit. Research facts
  (empirically verified on stock 3.8.4, full report:
  scratchpad `epic0-research.md`): CC + separation checking both work on
  stable, no flags; a jar built from CC-importing files is consumable by
  plain non-CC consumers with zero flags — no experimental taint.
- [x] 0.1b (done: 0800a83) Script/docs version pinning: examples pin no Scala version today
  (scala-cli 1.14.0 defaults to 3.8.3 — the origin of InStage.scala's
  "verified on 3.8.3" note); add `//> using scala 3.8.4` to examples +
  README script blocks, and teach the release tooling
  (`project/UpdateScalaCliVersionInDocs.scala` — currently bumps only dep
  coordinates) about the pin. The CC/separation language imports go into
  example scripts later, with 0.5/0.6 — enforcement is per compilation
  unit, so a user script only gets fork-capture checking if the `.sc` file
  itself carries the imports (record this in 0.7).
- [x] 0.2 (done: decided in tracker; shipped via 0.3/0.4) Design the capability split using the 3.8 vocabulary, which matches
  the ADR amendment 1:1: `InStage` (LLM-call gate) extends
  `caps.SharedCapability` — freely capturable into forks, exempt from
  separation rules; the workspace/index-mutation token carved out of it
  (`WorkspaceWrite`: git/gh writes, `fs.write`, progress-store writes) and
  `FlowControl` stay **exclusive** capabilities, so separation checking's
  hidden-set rules forbid two concurrent closures capturing them. The scope
  question the ADR amendment glossed is DECIDED (owner, 2026-07-07): the
  sharable gate covers **any** LLM call — no toolset-aware typing. Rationale:
  capability types can't constrain what the agent subprocess does (that lives
  in `AgentConfig.tools` + the `Enforcement` matrix); the exclusive token
  guards Scala-side index-like writes only; agents tampering with `.orca`
  state is adversarial and outside the threat model; and parallel
  Full-toolset agents sharing a worktree remains a documented user-level
  choice, not a type error. Record this in the ADR amendment (0.7) so the
  eventual CC design doesn't re-open it.
- [x] 0.3 (done: 7437569) Make the tokens trackable (amended with verified 3.8.4 stdlib
  facts: `caps.Capability` is **sealed** — extend the sub-markers, never the
  root; `caps.SharedCapability` is **non-experimental**, so `InStage.scala`
  needs no language import; `caps.ExclusiveCapability` is `@experimental`,
  so the `WorkspaceWrite` and `FlowControl` files need the CC import):
  re-represent `InStage` as a class extending `caps.SharedCapability`
  instead of opaque `Unit` (a `Unit` value is pure — CC has nothing to
  track); the mutation token and `FlowControl` extend
  `caps.ExclusiveCapability`. Keep the `RuntimeInStage` single-door funnel,
  `private[orca]` mints, and the `@implicitNotFound` texts (update them for
  the two-token world). Add a consumer-taint canary test (plain non-CC
  compilation unit using `FlowControl`/`WorkspaceWrite`) pinning the
  research's no-taint verdict against future compiler upgrades.
- [x] 0.4 (done: 365ea76 + f360350) Migrate mutating tool methods to the mutation token per 0.2's
  decision (git/gh writes, `fs.write`, progress-store writes; `agent.*.run`
  per the 0.2 answer). Most user code is unaffected (both tokens are ambient
  in stage bodies); helper signatures change — update the AGENTS.md
  helper-author guidance and both `implicitNotFound` messages.
- [x] 0.5 (done: 4011187) Fork-boundary enforcement — a bridge designed for deletion
  (amended: the research falsified the tracker's "already exists" claim —
  ReviewLoop's fan-out at `ReviewLoop.scala:383` calls **Ox's**
  `ox.flow.Flow.mapParUnordered` directly; there is no orca-owned
  combinator, and separation checking verifiably does NOT fire through a
  non-CC-compiled library's combinator, so orca wrappers are load-bearing):
  CREATE thin CC-compiled fork/mapPar wrappers, route the ReviewLoop fan-out
  through them; plain `=>` thunk parameters on a CC-compiled combinator
  suffice for separation checking to reject two closures sharing an
  exclusive capability (verified) — no exotic arrow annotations needed; raw
  `ox.fork` is the unchecked escape hatch in the interim. **Upstream plan (owner, 2026-07-07): Ox itself
  adopts capture checking, with the `Ox` capability tracked.** Once Ox
  annotates `fork`/`supervised`, the fork-boundary rejection of exclusive
  captures happens at `ox.fork` directly (shared `InStage` still passes —
  `SharedCapability` is separation-exempt), orca's wrappers stop being
  load-bearing for safety, and the escape-hatch caveat disappears. Keep the
  wrappers thin and coordinate their capture signatures with the Ox
  annotation design rather than finalizing them blind.
- [x] 0.6 (done: 0e8501d) Negative compile tests (amended: munit
  `compileErrors`/`typeCheckErrors` — the existing `orcacaps` mechanism —
  verifiably returns 0 errors on snippets that fail capture checking,
  because CC runs post-typer; CC negatives need **separate compilation**,
  e.g. dotc-invoked-from-test or scala-cli fixtures): fork capturing
  `FlowControl` fails; fork capturing `WorkspaceWrite` fails; fork capturing
  `InStage` **compiles** (pins the load-bearing fan-out capture so a future
  tightening can't silently outlaw it). Missing-given negatives stay on
  munit `compileErrors`.
- [x] 0.7 (done: a478f1b + 279441d + e1b15cf) Rewrite ADR 0018 §6's capture-checking bullet from deferred future
  work into the decided design (0.1–0.6's outcomes), including: the
  no-taint verdict (CC-importing files don't mark definitions experimental
  on 3.8.4 — consumers need nothing), the enforcement locus (per
  compilation unit — scripts must carry the imports to be checked), the
  0.2 scope decision, and the Ox-CC upgrade path for the 0.5 wrappers.

## Epic 1 — Total failure reporting: no silent exits

`flow()`'s outer catch (`case NonFatal(_) => failed = true`, "already surfaced
inside the scope") is only true for **body** failures — `FlowLifecycle.run`
wraps only `body` in its reporting try/catch. Setup aborts (including the
user-facing resume refusals R30 promises "a clear message" for), rehydration
failures, and `teardownSuccess` failures all exit 1 with banner + cost summary
and zero explanation. Fix 5.2 (reported-mark moved onto ctx) is what blinded
the outer frame. Absorbs open item 7.2 from the first tracker.
Refs: `runner/src/main/scala/orca/flow.scala:161-169`,
`runner/src/main/scala/orca/runner/FlowLifecycle.scala:46-73,215-228,299-313`.

- [x] 1.1 (done: eb74028) Introduce `private[orca] SurfacedFlowFailure(cause)` — thrown only
  after the failure was emitted/logged — and a `surfaced(...)` bracket in
  `FlowLifecycle.run` applied to **every** phase (setup, rehydrate, body,
  teardownSuccess). `flow()`'s catch matches `SurfacedFlowFailure` to discard,
  and falls back to a stderr print for anything else, so any future unsurfaced
  path is loud by construction.
  (Research 2026-07-07, epic1-research.md: the bracket must stay
  phase-agnostic — report/log/wrap only; `teardownFailure` remains coupled to
  the BODY failure path exclusively, never to setup/teardownSuccess failures.
  Body reporting keeps `reportOnce` so `runStage`'s existing first-report is
  not duplicated. Budget: `runFlow`'s exposed exception type changes —
  ~7 `FlowLifecycleTest` intercepts adapt deliberately.)
- [x] 1.2 (done: eb74028 + 988e222) `teardownFailure` throwing inside the body catch must not mask the
  original: `e.addSuppressed(t)`, rethrow `e`.
  Refs: `runner/.../FlowLifecycle.scala:71`.
- [x] 1.3 (done: fe3cf4e) Close first-tracker 7.2: uniform `bestEffort(what)(op)` helper (catch
  `NonFatal` + debug-log) for all three `teardownSuccess` legs
  (remove/commit/handoff), making the "cosmetic — swallowed" doc true. A
  successful run can then never exit 1.
  (Research correction 2026-07-07: commit/finishBranch legs already catch
  `NonFatal` broadly — the real gaps are (a) the `os.remove` leg's
  `NoSuchFileException`-only catch and (b) zero logging on all three legs.)
  Refs: `runner/.../FlowLifecycle.scala:308-319`.
- [x] 1.4 (done: eb74028) Route setup-phase warnings through the dispatcher — the "no event
  dispatcher threaded through setup" comment is stale (`ctx` demonstrably
  exists at the call site); a custom `Interaction` (Slack) currently never sees
  "starting fresh — the previous run's stages will re-run".
  (Research 2026-07-07: no `OrcaEvent.Warning` case exists — reuse
  `OrcaEvent.Step`, matching `GitTool.resetHard`'s existing convention.)
  Refs: `runner/.../FlowLifecycle.scala:48-49,182-199`.
- [x] 1.5 (done: eb74028 + 988e222) Verify `--verbose` prints stacks on the newly-surfaced paths (the
  `debug` stack-print currently lives only in the body catch).

## Epic 2 — `FlowSession`: one door for durable sessions

`agent.session(name, seed)` returns a bare `SessionId[B]` — the same type raw
`run(prompt, session)` accepts and defaults. Only the `runSeeded` extension
applies the seed, probes existence, and persists the wire id; the raw door
compiles, silently loses context, and on claude crashes deterministically
post-resume (no `resumeWireId` → `Fresh` dispatch → `--session-id` collides
with the existing on-disk transcript). The `AgentCall` scaladoc invites the
misuse; README lists the entry points as undifferentiated siblings; ADR 0018
R22 describes persistence the code only does for named sessions.
Refs: `flow/src/main/scala/orca/Session.scala:88-126`,
`tools/src/main/scala/orca/agents/AgentCall.scala:31-69`,
`flow/src/main/scala/orca/review/ReviewLoop.scala:461-470`,
`runner/src/main/scala/orca/runner/FlowLifecycle.scala:85-95`.

- [x] 2.1 (done: 1c665b0) Introduce `FlowSession[B]` (agent + `SessionId[B]`, `private[orca]`
  ctor): `session(name, seed)` returns it; its `run`/`resultAs` own the whole
  probe → seed/preamble → run → `persistResumeWireId` protocol in one place.
  No widening to `SessionId` — `.id` is the visible escape hatch.
  **CC note:** write `run`/`resultAs` against Epic 0.2's token set from the
  start — they persist to the progress store, so they take the fork-opaque
  mutation token, making "durable-session runs are flow-thread-only" a
  type-level fact once CC lands, while raw ephemeral `run` (2.2) stays
  fork-sharable. The two doors map 1:1 onto the capability split; do not ship
  2.1 with a plain `(using InStage)` signature that 0.4 would then break.
  (Research 2026-07-07, epic2-research.md: signature DECIDED —
  `(using InStage, WorkspaceWrite)` explicit, NOT self-minted; `session()`
  itself keeps self-minting since it must work outside stages. Method set:
  free-text run + structured `resultAs` are both proven necessary
  (ReviewLoop.fix uses the structured raw door today); interactive is
  deliberately deferred with a documented rationale, not silently omitted.)
- [x] 2.2 (done: 1c665b0) `AutonomousTextCall.run(prompt, session)` keeps `SessionId[B]` for
  ephemeral use only; scaladoc stops pointing log-backed sessions at it.
- [x] 2.3 (done: c903889) `reviewAndFixLoop` / `ReviewFixLoop.fix` take the bundled handle
  instead of a separate `coder + sessionId` pair (also fixes the fix-turn raw
  bypass).
  (Research: highest-risk item — ~26 call sites incl. 17 hand-constructed
  `SessionId` fixtures in ReviewAndFixTest/ReviewFixFlowTest; those tests
  live in `orca.review`, so they can construct the `private[orca]`
  FlowSession directly. Also update `FlowContext.scala:56-61`'s scaladoc,
  which cites the old reviewAndFixLoop signature.)
  Refs: `flow/.../review/ReviewLoop.scala:454-463`.
- [x] 2.4 (done: 64f446c, after 2.3 unblocked it) Drop (not deprecate) the `JsonData[SessionId[B]]` given.
  (Implementation note 2026-07-07: research's "zero internal clients" was
  falsified by the 2B implementer's stop-condition — FlowCompilesTest's
  review-loop canary persists the interactive door's `(SessionId, FlowPlan)`
  tuple as a stage result, i.e. the exact R22 attractive-nuisance pattern.
  Resolution: 2.3 migrates that canary to the named-session pattern FIRST;
  the given-drop then lands truly zero-client.) The given is the attractive
  nuisance: a session persisted as a stage result gets neither map
  persistence nor seed lookup. Amend ADR 0018 R22 to match reality.
- [x] 2.5 (done: e557e18 + 64f446c) Fix the AGENTS.md helper-authoring sentence: `Sessioned[B, A]` is a
  *result* pair, not the promised agent+session bundle — point it at
  `FlowSession[B]` once it exists.
  Refs: `AGENTS.md:52-59`, `flow/src/main/scala/orca/plan/Sessioned.scala:24`.
- [x] 2.6 (done: e557e18) Update README tool tables to present the durable door and the
  ephemeral door as different things.

## Epic 3 — Protected-branch safety on the fresh path

`RecoveryCheck` validates the branch name only when it comes from the progress
log header (resume). On a fresh run the name comes from `shortenPrompt` — a
cheap-model LLM reply — and `slug` guarantees ref-shape only:
`slug("main") == "main"`. A fresh run can bind the whole flow to the default
branch (stage commits + `reset --hard` teardown on it); with
`startBranch = "develop"` and an LLM-named `master`, teardown's empty-diff path
executes `git branch -D master`. Commit e99b361 added the check to the resume
arm only.
Refs: `runner/src/main/scala/orca/runner/FlowLifecycle.scala:206-213,239-260,324-338`,
`flow/src/main/scala/orca/BranchNamingStrategy.scala:78-88`,
`flow/src/main/scala/orca/progress/RecoveryCheck.scala:36-65`,
`tools/src/main/scala/orca/tools/GitTool.scala:253-258,453-461`.

- [x] 3.1 (done: c33dea0) Opaque `FeatureBranch` with one smart constructor owning the
  protected-set policy (`RecoveryCheck.alwaysProtected` + detected default
  branch), minted by **both** arms of `setup`; `validateHeader` delegates to
  the same constructor.
- [x] 3.2 (done: c33dea0, fallback-rename policy) `freshRun` maps a refusal to a deterministic fallback rename (mirror
  `slug`'s hash fallback) or aborts loudly — decide which.
  (Research 2026-07-07, epic3-research.md: DECIDED — deterministic fallback
  rename reusing the existing `flow-<hash>` shape + loud Step; abort would
  make unattended runs flake on a cosmetic collision, and the LLM-failure
  fallback idiom never aborts over naming. FeatureBranch lives in
  `flow/.../progress/` beside RecoveryCheck, smart ctor takes
  `(name, protectedBranches)`; FlowLifecycle computes the set once via
  `git.defaultBranch()` and passes it to BOTH arms. Split: 3A=3.1+3.2,
  3B=3.3+3.4 — checkoutOrCreate's only 3 callers are in FlowLifecycle;
  `createBranch`/`checkout` primitives already exist.)
- [x] 3.3 (done: c2d6bfc) `finishBranch` / lifecycle-level delete take `FeatureBranch`, making
  "delete an unvalidated name" unrepresentable (the current guard is only
  "never the current branch").
> Epic 3 residual (security review, 2026-07-07, recorded not fixed): the
> protected set is `{main, master} ∪ detected default` — a long-lived
> non-default branch (`develop`, `staging`) named as the header's feature
> branch could still be deleted by the throwaway cleanup on a narrow
> resume-only path (requires a hand-moved/tampered header + checked-out on
> it + matching promptHash + blank diff; reflog-recoverable; pre-existing,
> and this epic narrowed it for main/master/default). If it should close:
> either a user-configurable protected set on `flow(...)`, or refuse
> throwaway-delete for any branch orca's header didn't record as
> orca-created. Owner call.

- [x] 3.4 (done: c2d6bfc, checkoutOrCreate deleted) Split fresh-create from resume-checkout at the lifecycle layer
  (`createFlowBranch: Either[BranchAlreadyExists, Unit]` vs `checkout`) so
  silently adopting an unrelated pre-existing branch becomes a visible
  decision.

## Epic 4 — Turn-boundary grammar by construction

Fix 2.3 pinned the grammar in prose + a per-scenario test helper, but left
enforcement to five hand-rolled per-driver flag machines — four violate it
today: claude's `handleResultError` settles without closing an open turn (its
own deltas-then-error test omits `assertGrammar`) and emits empty turn ends on
suppressed-`ask_user`-only messages; codex pairs `AssistantTurnEnd` with
`agent_message` only, so tool-/reasoning-only turns settle open; gemini emits
unconditional turn ends (empty-turn violation); pi's `failWith` path never
closes. Consumers survive via `TurnBuffer`'s defensive flushes.
Refs: `tools/src/main/scala/orca/backend/ConversationEvent.scala:26-31`,
`claude/.../ClaudeConversation.scala:145-166,218-225`,
`codex/.../CodexConversation.scala:184-191,231-238`,
`gemini/.../GeminiConversation.scala:115-117`,
`pi/.../PiConversation.scala:128-142,179-181`,
`opencode/.../OpencodeConversation.scala:75-86,153-175`.

- [x] 4.1 (done: 64ba38d) Move turn accounting into `ForkedConversation`: the event queue
  tracks `openTurn` (reader-thread-confined var, same invariant as
  `settledOutcome`), drops empty `AssistantTurnEnd`s, and
  `succeedWith`/`failWith` auto-close an open turn before settling.
  (Research 2026-07-07, epic4-research.md: all five violations re-verified;
  every enqueue site already goes through the ONE `EventQueue.enqueue`
  funnel — the fix is one file, not five. Activity classification confirmed
  authoritative in ConversationEvent scaladoc + assertGrammar: activity =
  deltas/toolcall/toolresult; neutral = UserMessage/Error/ApproveTool/
  UserQuestion. CAVEAT to document: stderr/askUser forks also enqueue, but
  only neutral events — make that invariant explicit like settledOutcome's.
  Two scripted tests change observable sequences (claude deltas-then-error,
  codex tool-only asserting events.size==2) — update deliberately. Gemini's
  violation has zero current coverage — the base test must include it.)
- [x] 4.2 (done: 8b63cb0 — opencode/pi/gemini machinery deleted; codex/claude emissions KEPT as genuine intermediate boundaries) Delete the per-driver machinery it obsoletes: opencode's
  `activitySinceTurnEnd`/`failTurn`/`emitTurnEnd`, pi's
  `sawAssistantActivity`, gemini's unconditional pre-result turn end, claude's
  unconditional enqueue.
- [x] 4.3 (done: 8b63cb0 — mirrored pair collapsed to one flag; turnIsOpen deliberately NOT used (ToolResult-vs-prose semantic mismatch, documented)) Delete claude's mirrored `partialsSeenThisTurn` /
  `deltasSinceLastFullTurn` pair (7.9's fix duplicated state; base-owned
  accounting gives one derived value) and their ~25 lines of lockstep prose.
  Refs: `claude/.../ClaudeConversation.scala:52-77,126-128,146-148`.
- [x] 4.4 (done: 64ba38d, TurnGrammarTest) Test the grammar once in `tools` as a property of the base
  (including the deltas-then-error and tool-only-turn sequences the per-driver
  tests currently route around); demote the per-backend conformance helpers to
  wire-shape checks.
- [x] 4.5 (done: 8b63cb0 — flushes kept for abnormal termination, comments corrected) Simplify `TurnBuffer`'s defensive flushes if 4.1 makes them
  unreachable.

## Epic 5 — Hierarchical stage identity (nested stages + resume)

Stage ids are `name#occurrence` from a per-run *execution* counter; a resumed
(skipped) stage never runs its body, so its nested stages never bump the
counter. A later same-named stage then computes the inner stage's id: same
result type ⇒ silently skipped with the wrong stage's recorded value; different
type ⇒ re-runs and `appendEntry` upserts over the inner record. Needs no
script edit — only nesting + a crash. Same desync applies to
`nextSessionOccurrence` for `session(...)` calls inside stages. Nesting is
explicitly supported (ADR 0018 R12, §2.1).
Refs: `flow/src/main/scala/orca/Flow.scala:45-50,56-78`,
`runner/src/main/scala/orca/runner/DefaultFlowContext.scala:103-117`,
`flow/src/test/scala/orca/StageRuntimeTest.scala:84-98` (nesting tested, never
nesting + resume).

- [x] 5.1 (done: 72b009c — StageFrames mixin, shared by production + all test doubles) Make stage identity a path (`outer#0/inner#0`): `FlowControl` keeps
  a frame stack (thread-affine already), occurrence counters scoped per
  enclosing frame; children of a skipped parent become structurally
  unreachable. Log-format break is acceptable per the log's own per-run
  contract (`ProgressLog.scala:34-35`).
  **CC note:** the frame stack strengthens `FlowControl`'s thread-affinity
  requirement — aligned with CC making `FlowControl` fork-opaque (Epic 0.2);
  `enterStage`/`exitStage` live on `FlowControl`, nowhere else.
- [x] 5.2 (done: 72b009c — require guard, fires for any open frame) Decide session keying under nesting: same parent-scoped keying, or
  document + `require` that `session(...)` is called outside stages
  (`FlowControl` knows whether a frame is open).
  (Research 2026-07-07, epic5-research.md: DECIDED — the `require` guard.
  All 6 examples and ~15+ real call sites mint sessions at flow-body top
  level; FlowSession.run inside stages never touches the counter; 0
  violations exist. Parent-scoped keying would build for a case that
  doesn't occur. Also: id strings are fully opaque (exact-equality only) —
  path format is drop-in; TestFlowControl duplicates the flat-map bug and
  MUST be upgraded in the same change; Epic 7.1's future thread assert must
  cover enterStage/exitStage — note for that epic.)
- [x] 5.3 (done: 72b009c — both money shapes RED-first) Add the missing test: nesting + crash + resume, both the same-type
  (silent wrong value) and different-type (record clobber) shapes.
- [x] 5.4 (done: 72b009c — R10/§2.1/§5 amended, scoped retraction) Amend ADR 0018 (§2.1/§5) — the current "occurrence shifts ⇒ harmless
  re-run" claim doesn't cover this misattribution case.

---

# MEDIUM priority

## Epic 6 — Session write-side and id hygiene

The 1.x fixes funnelled the read side; the write side still has open doors.
(Research 2026-07-07, epic6-research.md: all six items verified valid and
unaddressed at 012cf35 — Epics 2/4/5 added prerequisite plumbing only. 6.6's
pi re-priming bug reproduces identically through the new FlowSession door
(effectivePrompt → sessionExists → Ephemeral⇒false). Three isSafe re-check
sites exist today, not two. Split: 6A = 6.1+6.2+6.6 (one SessionSupport/
registry reshape); 6B = 6.4→6.3→6.5, hard-ordered after 6A because 6.5's
target guard sites are defined by 6.2's commitAfterDrain.)

- [x] 6.1 (done: 7b345f0) `Dispatch.Fresh(claim: Option[WireSessionId[B]])`: `ClientToServer`
  registries pass `None` instead of laundering the client id through `onWire`
  (currently stamped as wire-safe for four backends where it never goes on the
  wire; a sixth backend forwarding it resurrects the pre-1.1 bug class with
  type blessing). `onWire` then lives exclusively in `ClaimedOnce`, matching
  its own doc.
  Refs: `tools/src/main/scala/orca/backend/SessionRegistry.scala:16-18,96-99`,
  `tools/src/main/scala/orca/agents/BackendTag.scala:81-86`,
  `claude/.../ClaudeArgs.scala:67-68`.
- [x] 6.2 (done: 7b345f0) Hide the registry inside `SessionSupport`; add `commitAfterDrain`
  (throwing guard) beside `register` (log-and-skip guard) so both write
  policies have one named home; `Conversations.runAutonomous`/`drainAndCommit`
  take `SessionSupport[B]`, so a backend physically can't hand the shell a
  registry different from its declared `sessions`. Fix the self-contradicting
  funnel doc; claude's unguarded interactive commit
  (`ClaudeBackend.scala:160`) goes through `register`.
  Refs: `tools/src/main/scala/orca/backend/SessionSupport.scala:26,50-71`,
  `tools/src/main/scala/orca/backend/Conversations.scala:184-224`.
- [x] 6.3 (done: 4a37301) `session(name, seed)` reuse path checks the recorded backend tag:
  mismatch ⇒ warn + mint fresh (upsert replaces record with the new tag);
  `persistResumeWireId` self-heals the tag instead of preserving a stale one.
  Today a lead-backend swap between runs re-stamps the wire id under the old
  tag ⇒ permanent silent re-seeding — the exact class 1.3 targeted, on the
  write path it didn't cover.
  Refs: `flow/src/main/scala/orca/Session.scala:42-74,111-126`,
  `runner/src/main/scala/orca/runner/FlowLifecycle.scala:104-126`.
- [x] 6.4 (done: 4a37301 — wire names frozen to pre-change toString) Type `SessionRecord.backend` as `BackendTag` with an explicit wire
  name (`enum BackendTag(val wireName: String)` + `fromWireName` + a pinning
  test, `EnforcementTableTest`-style) instead of `toString` /
  `values.find(_.toString == t)` — a case rename currently strands all
  persisted sessions silently. Warn (don't just skip) on an unknown tag at
  rehydration.
  Refs: `tools/.../agents/BackendTag.scala:10-15`,
  `flow/.../Session.scala:71`, `flow/.../progress/ProgressLog.scala:64-71`,
  `runner/.../FlowLifecycle.scala:108-119`.
- [x] 6.5 (done: 4a37301 — isSafe reduced to the two 6.2 policy doors) Parse-don't-validate ids: make unchecked
  `SessionId.apply`/`WireSessionId.apply` `private[orca]`; add
  `parse(s): Option[...]` as the only door for log/wire-sourced strings; the
  scattered `isSafe` re-checks reduce to the two policy sites of 6.2.
  Refs: `tools/.../agents/BackendTag.scala:20,78`,
  `flow/.../Session.scala:60`, `runner/.../FlowLifecycle.scala:121-126`.
- [x] 6.6 (done: 7b345f0 — willContinue; pi re-prime fixed) `runSeeded` asks "will run continue?" not "is it durable?": add
  `SessionSupport.willContinue` (Ephemeral ⇒ in-process
  `resumeWireId.isDefined`; Durable ⇒ probe). Today pi re-injects the full
  seed + progress preamble into a live conversation on **every** task of the
  canonical plan loop. Add an Ephemeral-shape test (`RunSeededTest` stubs the
  probe directly, so this is never exercised).
  Refs: `flow/src/main/scala/orca/Session.scala:88-99`,
  `tools/src/main/scala/orca/backend/SessionSupport.scala`,
  `pi/.../PiBackend.scala:54-61`.

## Epic 7 — Concurrency invariants get teeth

(Research 2026-07-07, epic7-research.md: all five valid/unaddressed.
Corrections+decisions: the 7.1 assert lives in StageFrames itself (all impls
incl. test doubles get it free); Ox 1.0.5 fork = always a fresh thread
(verified in the jar); the CC note OVERSTATED — compile-time enforcement
covers exactly one internal call site (ReviewLoop), so the runtime assert is
the ONLY enforcement for user scripts, not a backstop. 7.4: in-process
AtomicBoolean + .orca/flow.lock with PID-liveness (steal-with-warning if
dead, refuse if alive). 7.5: resultAs[O] bypasses BaseAgent per call — gate
BOTH autonomous.run and resultAs construction. Split: 7A=7.1+7.4+7.5,
7B=7.3-then-7.2.)

- [x] 7.1 (done: d3f9bd9 — assert in StageFrames; only enforcement for user scripts) R12 bridge enforcement: `DefaultFlowContext` records `ownerThread`;
  `nextOccurrence`/`nextSessionOccurrence` assert thread identity and throw
  "stage(...)/session(...) called from a fork — forks get FlowContext only
  (ADR 0018 R12)". Ox forks are always fresh threads, so the check is exact;
  converts silent progress-log corruption into a first-run error.
  **CC note:** deliberate bridge — Epic 0 makes the capture a compile error,
  and since scripts always compile with our pinned toolchain, that covers all
  consumers. The assert still ships and stays: CC/separation checking is
  experimental and evolving, and the assert also catches non-capture leaks
  (e.g. a context stored in a global) that CC can't see. Keep it one line so
  there's nothing to unwind.
  Refs: `runner/.../DefaultFlowContext.scala:99-118`,
  `flow/.../Flow.scala:45-50`.
- [x] 7.2 (done: 9e29067 — prompt[A] transaction, fair semaphore) Replace `TerminalOutput.suspend()`/`resume()` with one bracketed
  `prompt[A](readUser: () => A): A` transaction (fair-semaphore-serialized,
  suspend/resume private to the impl). Two concurrent interactive prompts —
  a sanctioned fork composition — currently interleave the non-reentrant
  boolean and share one process-global JLine reader.
  Refs: `runner/.../terminal/TerminalOutput.scala:42-47,88-89,123-181`,
  `runner/.../terminal/ConversationRenderer.scala:200-230,324-343`,
  `runner/.../terminal/TerminalInteraction.scala:60-68`.
- [x] 7.3 (done: 9e29067) `TerminalOutputState.setStatus` respects `suspended` (store the
  label, skip `drawStatus()`; resume already redraws) — a `StageStarted`
  during a prompt currently repaints over a live `readLine`.
  Refs: `runner/.../terminal/TerminalOutput.scala:138-152`.
- [x] 7.4 (done: d3f9bd9 — AtomicBoolean + PID-liveness lock file) Guard `flow()` reentrancy/concurrency: process-wide flag (+
  workdir-keyed lock file for the two-process case) failing fast with "a flow
  is already running in this working tree". A nested `flow` today stashes the
  outer flow's tree, switches branches under it, and `reset --hard`s its work.
  Refs: `runner/src/main/scala/orca/flow.scala:115-169,249-261`.
> Epic 7 residual (7A review, 2026-07-07, recorded not fixed): a NESTED
> public `flow()` call fail-fasts correctly at the process-lock guard, but
> its System.exit(1) skips the OUTER flow's finallys — outer branch stays
> checked out and `.orca/flow.lock` remains (self-heals: next run steals the
> dead-PID lock with a warning). Undefined path per ADR 0018 §6, strictly
> better than the prior silent corruption. Full fix = the outer teardown
> surviving an inner exit — owner call whether to pursue. Also noted:
> the git-exclude for the lock assumes workDir = repo root (true for all
> current usage).

- [x] 7.5 (done: d3f9bd9 — latch on shared AgentBackend (copyTool-bypass-proof)) Agents must not outlive their flow silently: `close()` flips a
  `closed` flag in `BaseAgent`; run entry points throw "agent used after its
  flow ended" (leaked agents currently emit to a closed run's dispatcher —
  loud on opencode, invisible on claude/codex).
  **CC note:** bridge with a concrete upgrade path — once agents are built
  via `AgentWiring => Ox ?=> A` factories (10.2) *and* Ox's planned CC
  adoption lands (the `Ox` capability itself tracked — see 0.5), agent values
  capture the flow's Ox scope and the leak becomes a compile error; the flag
  stays for temporal misuse CC can't express (use-after-close within the
  scope is a lifetime property, not a capture one).
  Refs: `runner/.../DefaultFlowContext.scala:62-74`,
  `tools/.../agents/Agent.scala:206`.

## Epic 8 — Backend SPI seams

(Research 2026-07-07, epic8-research.md: all six valid, none touched by
Epics 4/6/7. 8.2 is BROADER than tracked: Conversations.runAutonomous also
finally-cancels, so successful autonomous opencode turns fire /abort too.
8.6's enforcement default has 7 test doubles, not 6. Split: 8A=8.1 solo
(widest mechanical surface first), 8B=8.2+8.3 (opencode lifecycle),
8C=8.4+8.5 (driver wire/stderr layer), 8D=8.6 (scattered mechanical, last).)

> Epic 8 residual (epic review, 2026-07-08, recorded not fixed;
> pre-existing): `commitAfterDrain`'s empty/unsafe-wire-id rejection throws
> a plain OrcaFlowException, which the retry policy treats as RETRYABLE — a
> backend build that reproducibly omits its session id retries 3x before
> failing. The Epic 8 gemini fail-loud change makes the cause visible near
> the failure; making structural-id rejections non-retryable is a separate
> owner call (touches retry classification, first-pass item 5.1's design).

- [x] 8.1 (done: f4631f4 — workDir per-backend, probe/spawn unified by construction) Make `workDir` per-backend (fixed at wiring), not per-call: the SPI
  parameter is a phantom degree of freedom the runtime never varies, opencode
  ignores it entirely, and claude's probe/spawn "MUST match" contract is
  prose — the 7.5 bug class, still representable. Interim: `require` equality
  in opencode + claude's spawn path.
  Refs: `tools/.../backend/AgentBackend.scala:32`,
  `opencode/.../OpencodeBackend.scala:62-67`,
  `claude/.../ClaudeBackend.scala:52-61,106-113`.
- [x] 8.2 (done: 016d49e + c00cee5 — onCancelRequested hook, settledOutcome volatile) `ForkedConversation.onCancelRequested()` hook running inside the
  idempotence guard and only when not finalized; opencode's abort moves there.
  Today every **successful** opencode turn posts a real `/abort` (via the
  happy-path `finally cancel()`) for a session that may be resumed next turn,
  errors swallowed.
  Refs: `opencode/.../OpencodeConversation.scala:61-65`,
  `tools/.../backend/Conversations.scala:216-224`,
  `tools/.../agents/AgentCall.scala:252-253`.
- [x] 8.3 (done: 016d49e — SSE reorder + bracket) Fix opencode's open-path SSE leak: `http.events()` opens the stream
  in argument position before `serverSessionFor` can throw; nothing closes it.
  Reorder (throwing step first) + bracket, or generalize
  `SubprocessSpawn.open` over `StreamSource`.
  Refs: `opencode/.../OpencodeBackend.scala:97-105,117-127`,
  `opencode/.../JavaNetOpencodeHttp.scala:65-80`.
- [x] 8.4 (done: 98b4c94 — pipeline hoisted, ANSI stripped everywhere) Hoist the stderr pipeline (strip control sequences → trim → noise
  filter → enqueue `Error` → `recordStderr`) into
  `BufferedStderrDiagnostics`; backends override only the noise predicate.
  Triplicated today with demonstrated drift (only pi strips ANSI).
  Refs: `codex/.../CodexConversation.scala:110-114`,
  `gemini/.../GeminiConversation.scala:89-93`,
  `pi/.../PiConversation.scala:95-99`.
- [x] 8.5 (done: 98b4c94 — session_id required, Role enum (Unknown dropped)) Gemini wire parsing: required fields for identity-critical keys
  (`session_id` on `init` — a missing key currently becomes `Init("")`
  surfacing three retries later); type the role decision
  (`enum Role { User, Assistant, Unknown }`, `Unknown` dropped — an absent
  role currently classifies as assistant output). Codex already made the
  fail-loud choice at the same protocol position.
  Refs: `gemini/.../jsonl/InboundEvent.scala:16-18,56-62,104-107`,
  `gemini/.../GeminiConversation.scala:155-158`,
  contrast `codex/.../jsonl/InboundEvent.scala:170-173`.
- [x] 8.6 (done: ef9bce9 — SessionMode helpers, trait-only defaults, shared deleteFileResource, enforcement abstract) Small seam de-duplication: `SessionMode.displayPrompt`/`fold`
  helpers (destructuring repeated in all five backends, twice in opencode);
  `runAutonomous` default args declared on the trait only (per-impl
  re-declaration diverges silently by static type); shared
  `deleteFileResource` helper next to `SubprocessSpawn` (claude/codex mirror
  comment); consider making `AgentBackend.enforcement` abstract (test doubles
  pay one line each; backend #6 forgetting it currently ships `Ignored`
  silently).
  Refs: `claude/.../ClaudeBackend.scala:205-214,336-337`,
  `codex/.../CodexBackend.scala:178-181,251-252`,
  `tools/.../backend/AgentBackend.scala:112-113`.

## Epic 9 — Tools-layer classification: parse wire truth at the boundary

(Research 2026-07-08, epic9-research.md: all five valid. push() has zero
Left-branching callers today (examples .orThrow) — 9.1 low-risk; GH006 test
inversion needs no coordination. 9.3 placement DECIDED: detection logic in
JsonSchemaGen's funnel, timing via hoisting the derivation into
DefaultAgentCall's ctor as an eager val (fires at resultAs[O] construction);
tracker's cited AgentCall line refs drifted — actual call sites :173/:261.
Split: 9A=9.1+9.2, 9B=9.3+9.4+9.5.)

- [x] 9.1 (done: a6dc2d0 — two-case PushFailure; Other deliberately dropped (no third recoverable shape, zero Left-branching callers)) `PushFailure` ADT (`NonFastForward` / `RemoteDeclined(msg)` /
  `Other`): the bare `"rejected"` matcher classifies GitHub's protected-branch
  decline (GH006) as the recoverable `PushRejected`, whose documented
  fetch-and-rebase recovery loops forever; the test currently pins the
  misclassification — fix it to pin the correct class.
  Refs: `tools/src/main/scala/orca/tools/GitTool.scala:52-58,524-530`,
  `tools/src/test/scala/orca/tools/CliFailurePredicatesTest.scala:17-18`.
- [x] 9.2 (done: a6dc2d0 — CheckState total enum, EXPECTED->Pending, Unknown logged) Parse each `GhCheck` into a total `CheckState` enum
  (`Pending/Success/Failure/Unknown(raw)`) at the DTO boundary: legacy
  `EXPECTED` (required external CI not yet reporting) currently falls through
  to instant `Failure`, defeating the empty-rollup/`noChecksGrace` machinery
  built precisely to avoid racing CI startup; `Unknown` gets logged instead of
  vanishing into `else Failure`. Add the missing EXPECTED test.
  Refs: `tools/src/main/scala/orca/tools/GitHubTool.scala:588-615`,
  `tools/src/main/scala/orca/tools/GhJson.scala:15-22`.
- [x] 9.3 (done: c1c2f6e — funnel detection + eager ctor derivation; note: FlowSession structured door still derives at .run() (pre-existing, final-review list)) `JsonSchemaGen` fails fast on `Map[String, _]` fields with an
  actionable message ("model as a List of key/value case classes") instead of
  outsourcing to codex's opaque `invalid_json_schema` after destructive stages
  already ran; ideally hoist to `resultAs[O]` construction so it fires before
  any stage.
  Refs: `tools/src/main/scala/orca/util/JsonSchemaGen.scala:59-73`,
  `tools/src/main/scala/orca/agents/AgentCall.scala:146,235`.
- [x] 9.4 (done: c1c2f6e) `BuildStatus` carries `checkCount`/`hasChecks` — `waitForBuild`
  currently derives "a check has registered" from the rendered log string
  being non-empty (semantic fact destroyed by projection, re-derived from
  rendering).
  Refs: `tools/src/main/scala/orca/tools/GitHubTool.scala:77,470-499`.
- [x] 9.5 (done: c1c2f6e) `FsTool.list` validates glob shape at entry (reject leading `/`,
  handle `.`/`..`) with a self-describing error — currently an unrelated
  `IllegalArgumentException` from os-lib, and absolute globs could only ever
  return `Nil`.
  Refs: `tools/src/main/scala/orca/tools/FsTool.scala:42-66`.

## Epic 10 — Wiring and selector holes

(Research 2026-07-08, epic10-research.md: all four valid. 10.1 nuance: the
Epic-7 latch already covers copyTool-derived selectors (_.claude.opus) —
the hole is genuinely-foreign backends; the wired-check must compare
AgentBackend identity, NOT Agent eq (siblings aren't eq — naive check
false-positives on the common pattern). 10.3 additions: CostTracker
(invited by flow.scala's own scaladoc) and PushFailure (reachable via
exported GitTool.push) also missing; CheckState correctly not exportable.
10.4: targetAgent's match is in FlowLifecycle, not DefaultFlowContext; the
Map is a derived private field — the five typed accessors stay. Split:
10A=10.2+10.4+10.3 (mechanical+additive), 10B=10.1 (behavioral).)

- [x] 10.1 (done: 02ad9fe + c04fbb0 — backendIdentity via shared closedFlag; close-guard masking fixed) The lead-agent selector re-opens the event-blind hole 7.8 closed
  for overrides: `flow(args, _ => myPrebuiltAgent)` compiles; the foreign
  agent is event-blind, never closed, used privileged. Fix: `close()` also
  closes `ctx.agent` when not one of the wired five; construction-time warning
  when the selected agent isn't derived from this run; document the constraint
  in the `flow` scaladoc beside the override-factory paragraph.
  Refs: `runner/src/main/scala/orca/flow.scala:88-90`,
  `runner/.../DefaultFlowContext.scala:62-74,85`.
- [x] 10.2 (done: ac4e252 — param deleted, factory shapes unified; named-val adaptation caveat documented) Delete the `opencodeLauncher` flow parameter (3.1 residue): fully
  expressible as `opencode = Some(w => OpencodeAgents.default(w, launcher))`,
  and passing both today silently ignores the launcher. While there, unify all
  five factory fields to `Option[AgentWiring => Ox ?=> A]` (Scala 3
  auto-adapts plain lambdas, so user code compiles unchanged; the `.map(f =>
  f(agentWiring): OpencodeAgent)` ascription trick disappears). The uniform
  `Ox ?=>` shape is also what lets CC later tie agent lifetimes to the flow
  scope (see 7.5's note).
  Refs: `runner/src/main/scala/orca/flow.scala:106-107`,
  `runner/.../FlowWiring.scala:36-37`,
  `runner/.../DefaultFlowContext.scala:159-167`.
- [x] 10.3 (done: ac4e252 — Usage/Cost/CostTracker/IgnoredIssue(s)/PushFailure + canary) `exports.scala` gaps: add `Usage` (pattern-matched by any
  `OrcaListener`), `IgnoredIssues`/`IgnoredIssue` (return type of exported
  `reviewAndFixLoop`/`fixLoop`), and consider `Cost`.
  Refs: `runner/src/main/scala/orca/exports.scala`.
- [x] 10.4 (done: ac4e252 — derived Map + agentFor seam) Collapse the triplicated five-agent enumeration in
  `DefaultFlowContext` (`close`'s list, `targetAgent`'s match, `withDefaults`)
  into one `agents: Map[BackendTag, Agent[?]]`.
  Refs: `runner/.../DefaultFlowContext.scala`.

## Epic 11 — Review loop: roster-bound selection

`ReviewerSelector.prepare` still returns `List[ReviewBatch] => List[Agent[?]]`,
so the loop carries four runtime defenses with one root (the type permits
non-roster values): the name-uniqueness `require`, `resolveAgainstRoster` +
foreign-drop warning, the surprising silent full-roster fallback, and the
two-hop `.as[RB]` soundness prose.
Refs: `flow/.../review/ReviewLoop.scala:254-258,302-326,416-429`,
`flow/.../review/ReviewerSelector.scala:20-25`,
`flow/.../review/SelectedReviewers.scala:11-12`.

> Research 2026-07-08 (epic11-research.md): 11.2 already implemented (the
> floor matches `eligible` — folds into 11.1's retyping); 11.4's "12
> params" stale — 11 now (2.3 collapsed the pair); pure-arrow is genuinely
> typeable now BUT ReviewerSelector.scala's two inline `derives` DTOs must
> move to a sibling non-CC file first (the FixRequest precedent); the 4
> foreign-selector tests become inexpressible under identity keying —
> rewrite deliberately. Order: 11.3+11.4 (mechanical) FIRST, then 11.1+11.2.
> Untyped deletion: try the existential SessionEntry pair (run-inside-the-
> wrapper, the original epic sketch); keep Untyped only if it fights CC.

- [x] 11.1 (done: f3956ff — RosterEntry + pure arrow + existential SessionEntry; Untyped and .as[RB] DELETED; four defenses gone) Opaque roster-bound `RosterEntry` handles (`private[review]` ctor);
  selectors permute what they were handed; session map keyed by entry
  identity. `resolveAgainstRoster`, its warning, the fallback, and the
  uniqueness `require` all delete; `.as[RB]`'s justification collapses to one
  locally-visible hop (or deletes entirely if Epic 2's handle lands —
  `SessionId.Untyped`'s only client is this file).
  **CC note:** the returned closure's "must not capture `InStage`" contract
  (first-pass fix 4.3) becomes typeable as a pure arrow —
  `List[ReviewBatch] -> List[RosterEntry]` — once Epic 0 lands; declare it
  that way then.
- [x] 11.2 (done: f3956ff — floor preserved through the retyping (was already eligible-scoped)) Keep the hallucinated-picker-output floor inside `agentDriven`,
  matching returned names against `eligible: List[RosterEntry]`.
- [x] 11.3 (done: ac83142 — stopPolicy single-sourced) Extract the duplicated fix-loop stop policy (max-iterations
  counting fixes ⇒ N+1 evaluations, fold-to-ignored on cap, halt on
  zero-fixed) into one decision function used by both `fixLoop.loop` and
  `ReviewFixLoop.run.loop` — currently synced only by "same stop policy as"
  comments.
  Refs: `flow/.../review/ReviewLoop.scala:45-68,489-521`.
- [x] 11.4 (done: ac83142 — ReviewLoopConfig bundles the internal mirror) `ReviewLoopConfig` case class for `reviewAndFixLoop`'s 12 params
  mirrored field-for-field into `ReviewFixLoop`'s constructor (same shape 3.6
  fixed with `FlowWiring`).

---

# LOW priority

## Epic 12 — Diagnostics and small robustness

> Added 2026-07-07 (discovered operationally — /tmp exhaustion mid-effort):
> the test suites leak temp git-repo fixtures at scale. `os.temp.dir(...,
> deleteOnExit = true)` cannot remove non-empty trees (File.deleteOnExit
> semantics), so every TempRepo/GitRepo-based test leaves its whole workdir
> behind — ~35k dirs / one day's runs filled an 8G tmpfs. Fix shape: a
> shared munit fixture that recursively removes the temp root on teardown
> (os.remove.all in afterEach/afterAll), suite-wide. Treat as a 12.x task.
> RESOLVED (82b6de5 + b404651): TempDirs registry + shutdown hook; 78 sites
> migrated; 0 new leaked dirs verified operationally; interactive-session
> caveat documented at the fixture.

## Epic 12 — Diagnostics and small robustness

- [x] 12.1 (done: Task 12A — `cause: Throwable | Null = null` + `initCause`
  added to `AgentTurnFailed`, threaded at both wrap sites) `AgentTurnFailed`
  keeps cause chains: add
  `cause: Throwable | Null = null` + `initCause` and thread the original at
  both wrap sites (`awaitResult`, `runAutonomousWithRetry`) — debug stacks
  currently lose the driver's original failure twice over.
  Refs: `tools/.../backend/ForkedConversation.scala:167-171`,
  `tools/.../agents/AgentCall.scala:218-222`.
- [x] 12.2 (done: Task 12A — reader thread captured once in `runReader`,
  plain `assert` in `succeedWith`/`failWith` only; `cancel()`'s cross-thread
  `isSettled` read untouched) Make `settledOutcome`'s single-thread invariant
  self-enforcing:
  record the reader thread, assert in `succeedWith`/`failWith` (plain or
  `OrcaDebug`-gated) — currently a frozen five-backend audit comment that
  expires silently on backend #6.
  Refs: `tools/.../backend/ForkedConversation.scala:79-94,192-201`.
- [x] 12.3 (done: Task 12A — doc-only; `resetHard` trait doc + ADR 0018 §2.5
  R5 note both state untracked survival and the stash co-mingling; the
  scoped-clean decision stays open, as scoped) `resetHard` doc/decision: `git
  reset --hard` does not remove
  untracked files, so a failed stage's *new* files (the typical agent output)
  survive teardown and get stashed into the next run's "orca: starting flow"
  stash alongside user WIP. Fix the trait doc + ADR 0018 §2.5 note; if
  leftovers should die, that's a scoped clean of run-touched paths, not
  blanket `clean -fd`.
  Refs: `tools/src/main/scala/orca/tools/GitTool.scala:132-138`,
  `runner/.../FlowLifecycle.scala:176-180,344-347`.
- [x] 12.4 (done: Task 12A — `appendEntry`/`upsertSession` route through
  `loadDetailed()`, branching `Absent` vs `Corrupt(reason)` into distinct
  messages) Route `ProgressStore` write-path reads through `loadDetailed()`:
  a mid-run corrupted log currently throws "appendEntry called before
  writeHeader" — a protocol violation that never happened.
  Refs: `flow/.../progress/ProgressStore.scala:100-126`.
- [x] 12.5 (done: Task 12A — prefix fallback gated on an 8-digit dated-suffix
  remainder; `gemini-2.5-flash-lite` no longer matches `flash`) `Pricing.lookup`
  prefix fallback can cross model tiers
  (`gemini-2.5-flash-lite` billed as `flash`): gate the fallback on a
  date-like remainder (the case the heuristic was built for), or accept and
  note.
  Refs: `flow/src/main/scala/orca/events/Pricing.scala:79-89`.
- [x] 12.6 (done: Task 12A — `showThinking` deleted; `AssistantThinkingDelta`
  is now unconditionally a no-op, matching production's only-ever-false
  wiring and structurally removing the shared-buffer mis-styling risk) `showThinking` is dead in production and mis-styles the whole turn
  when enabled (shared `textBuffer`, first-delta-wins styling): delete the
  flag, or flush on style change so "one buffer, one style" is structural.
  Refs: `runner/.../terminal/ConversationRenderer.scala:40,67-73,94-98,118-126`.
- [x] 12.7 (done: Task 12A — `TokensUsed` gained `role: Option[String]`;
  `Agent.role`/`withRole` threaded through `BaseAgent` + all 5 backends;
  reviewer loop tags via `withRole("reviewer")` instead of renaming; the
  `"reviewer: "` string is now purely `CostTracker.summary`'s display
  derivation from `role`, plus a `perRole`/`perRoleCost` subtotal axis)
  Structured cost attribution: `TokensUsed(agent, role, model,
  usage)` with `role = "reviewer"` set at the emission edge — the `"reviewer: "`
  prefix is the last stringly convention in the event vocabulary; today
  "grouping" is only lexical sort adjacency, and any consumer parsing it back
  reintroduces the strip/re-match bug class.
  Refs: `flow/.../review/Reviewers.scala:31-39`,
  `flow/.../events/CostTracker.scala:112-141`,
  `tools/.../events/OrcaEvent.scala:40`.

---

## Re-litigated and upheld (second-pass verdicts; listed to prevent a third pass)

Challenged with fresh arguments and confirmed fine: `InStage` machinery
proportionality (it already *is* the context-function encoding; `erased` buys
nothing; capture checking remains the endgame); `ForkedConversation`'s core
channel/fork/outcome design; the dual `ConversationEvent`/`OrcaEvent`
vocabulary; the enforcement matrix + `EnforcementTableTest`;
`FlowContext`/`FlowControl` split; `EventDispatcher` quarantine semantics;
`ProgressStore` atomic writes + corruption-as-data; `RecoveryCheck`/`slug`
producer-validator sharing; the module graph; `FlowWiring`/`AgentWiring`
factories; `ReviewFixLoop`'s single `@tailrec` state threading; flattened LLM
wire DTOs (`AssessedPlan`, `BugTriage`); `CostTracker`/`Pricing` state
handling; claude's at-spawn interactive commit timing (CLI-imposed,
documented); `AskUserEchoes` per-backend matchers; the three renderings of
`AskUserMcpServer.ToolTimeout`; `SystemPromptComposer`'s file-vs-fold split;
claude `pipeStderr=false`; codex/gemini/opencode probes; pi `Ephemeral` shape;
`OpencodeServer`'s residual atomics (each closed race documented);
`JLinePrompter`'s process-scoped one-shot close (documented boundary);
`e9d751d`'s two-sided structured-result contract; `TerminalOutput` actor
design (Epic 7 is about the prompt protocol on top of it, not the actor);
`TerminalEventListener` post-8.6; `Text`/`Ansi`/`ToolCallLine`/
`ToolInputSummary`; `OsGitTool`'s helper layering, push credential handling,
`nonInteractiveEnv`; `OsGitHubTool`'s `ghRead`/`ghMutate` retry split;
`GhJson` DTO separation; `CliRunner`/`QuietProc` post-8.4; `RawJson`;
`PromptResource`; `TerminalControl`; `TextWrap`; `OrcaDebug`; `Usage`
normalisation; `SessionSupport`'s Durable/Ephemeral collapse (Epic 6 items are
missing pieces, not flaws in the collapse); the 6.1 "lean-in" tag-keeping
decision (Epic 2.5 fixes its mis-sold doc, not the decision).

First-pass verdicts that did **not** survive re-litigation: the centralized
stderr matchers (9.1 — breadth contradicts the Left type's recovery
semantics) and the 6.1 docs-only resolution (2.5 — `Sessioned` can't do what
AGENTS.md claims).
