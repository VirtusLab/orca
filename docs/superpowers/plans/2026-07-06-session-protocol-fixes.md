# Session Identity & Resume Protocol Fixes (findings 1.1–1.7) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the session-identity/resume protocol structurally safe: wire ids get their own type (`WireSessionId`), backend durability becomes one SPI value (`SessionSupport`) instead of three optional overrides, session records carry their backend, and sessions get stage-style name-based identity.

**Architecture:** Four mechanical-but-cross-cutting refactors of the tools-module SPI plus the flow/runner session plumbing, in dependency order: (1) type split, (2) durability restructure on top of the new types, (3) tagged rehydration, (4) named sessions. Docs and tracker close it out; a multi-agent review pass finishes.

**Tech Stack:** Scala 3 (braceless), Ox, jsoniter-scala via `JsonData`, sbt multi-module (`tools` ← `flow`/`claude`/`codex`/`gemini`/`opencode`/`pi` ← `runner`).

## Global Constraints

- Project MUST compile with zero warnings (`-Wunused:all`, `-Wvalue-discard`, `-Wnonunit-statement` are on).
- Braceless Scala 3 syntax only; every public def/val has an explicit return type.
- Before each commit: `sbt --client scalafmtAll`.
- Full verification per task: `sbt --client "compile; Test/compile; test"` (integration tests self-skip when the respective CLIs are absent).
- Each task ends with a commit; commit messages end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Findings tracker: `/home/adamw/orca/complexity-review.md` §1 items get `[x]` + commit hash in Task 5.
- All paths below are relative to `/home/adamw/orca`.

---

### Task 1: `WireSessionId` type split (finding 1.1, folds in 1.6 and the opencode `drainAndCommit` switch)

**Files:**
- Modify: `tools/src/main/scala/orca/agents/BackendTag.scala`
- Modify: `tools/src/main/scala/orca/backend/AgentResult.scala`
- Modify: `tools/src/main/scala/orca/backend/SessionRegistry.scala`
- Modify: `tools/src/main/scala/orca/backend/Conversations.scala`
- Modify: `tools/src/main/scala/orca/backend/AgentBackend.scala` (signatures only in this task)
- Modify: `tools/src/main/scala/orca/agents/Agent.scala`, `tools/src/main/scala/orca/agents/BaseAgent.scala`, `tools/src/main/scala/orca/agents/AgentCall.scala`
- Modify: `flow/src/main/scala/orca/Session.scala` (types only), `runner/src/main/scala/orca/runner/FlowLifecycle.scala` (types only)
- Modify: all five backend modules (details below)
- Test: `tools/src/test/scala/orca/backend/ConversationsTest.scala`, plus compiler-led adaptations across module tests

**Interfaces:**
- Produces: `opaque type WireSessionId[B <: BackendTag]` with `WireSessionId.apply`, `.value`, and `SessionId#onWire`; `AgentResult.wireId: WireSessionId[B]` (field renamed from `sessionId`); `SessionRegistry.commitSuccess(client: SessionId[B], server: WireSessionId[B])`, `resumeWireId(client): Option[WireSessionId[B]]`, `Dispatch.{Fresh,Resume}(wireId: WireSessionId[B])`; `Agent.resumeWireId: Option[WireSessionId[B]]`, `Agent.registerResumeWireId(client: SessionId[B], wireId: WireSessionId[B])`.
- Consumed by Tasks 2–4.

- [ ] **Step 1: Write the failing test** — in `tools/src/test/scala/orca/backend/ConversationsTest.scala` add (adapting to the file's existing stub-conversation helpers):

```scala
test("drainAndCommit commits the reported wire id against the caller's client id"):
  val client = SessionId.fresh[BackendTag.Codex.type]
  val reportedWire = WireSessionId[BackendTag.Codex.type]("server-thread-42")
  val registry = new SessionRegistry.ClientToServer[BackendTag.Codex.type]
  val conv = stubConversation(result = stubResult(wireId = reportedWire))
  val result = Conversations.drainAndCommit("codex", conv, client, registry)
  assert(result.wireId == reportedWire)          // result reports the wire truth
  assert(registry.resumeWireId(client).contains(reportedWire)) // registry learned it
```

- [ ] **Step 2: Run it** — `sbt --client "tools/testOnly orca.backend.ConversationsTest"`. Expected: does not compile (`WireSessionId` not found) — that is the failure mode for a type-split task.

- [ ] **Step 3: Introduce the type.** In `BackendTag.scala`, after the `SessionId` object, add (same file so both opaque types are mutually transparent):

```scala
/** The id a backend actually resumes a conversation against on the wire —
  * distinct from [[SessionId]], orca's stable client handle. For claude (and
  * pi's claimed ids) the two coincide; for codex/gemini/opencode the wire id
  * is a server-minted thread id learned from the protocol. A separate opaque
  * type makes returning a wire id as the caller's handle — or resuming
  * against a client id — a compile error (the bug class behind two shipped
  * resume bugs; see complexity review finding 1.1).
  */
opaque type WireSessionId[B <: BackendTag] = String

object WireSessionId:
  def apply[B <: BackendTag](value: String): WireSessionId[B] = value
  extension [B <: BackendTag](id: WireSessionId[B]) def value: String = id

extension [B <: BackendTag](id: SessionId[B])
  /** The client id used verbatim on the wire — the one legitimate crossing
    * between the two id spaces, for registries where the caller-allocated id
    * IS the wire id ([[orca.backend.SessionRegistry.ClaimedOnce]]).
    */
  def onWire: WireSessionId[B] = id
```

- [ ] **Step 4: Rename `AgentResult.sessionId` → `wireId` and retype.** In `AgentResult.scala`:

```scala
case class AgentResult[B <: BackendTag](
    /** The session id the backend reported for this turn — the WIRE id (server
      * thread id for codex/gemini/opencode; the claimed client id for
      * claude/pi). Callers wanting the stable client handle already hold it —
      * they passed it in; this field exists so the registry can learn the
      * mapping. */
    wireId: WireSessionId[B],
    output: String,
    usage: Usage,
    model: Option[Model] = None
)
```

- [ ] **Step 5: Retype the registry.** In `SessionRegistry.scala`: `Dispatch.Fresh(wireId: WireSessionId[B])` / `Resume(wireId: WireSessionId[B])`; `commitSuccess(client: SessionId[B], server: WireSessionId[B])`; `resumeWireId(client: SessionId[B]): Option[WireSessionId[B]]`. `ClaimedOnce` uses `client.onWire` in `dispatchFor`/`resumeWireId`; `ClientToServer` stores `WireSessionId.value(server)` and returns `WireSessionId[B](...)`. **Also (finding 1.6):** replace the stale header sentence `(claude at spawn, codex post-drain)` with: `(autonomous turns commit after a clean drain via Conversations.drainAndCommit; claude's interactive path commits at spawn; other interactive paths commit after the drive settles)`.

- [ ] **Step 6: `Conversations.drainAndCommit`** — commit `registry.commitSuccess(session, result.wireId)`; rewrite the scaladoc paragraph about per-backend re-stamping: the result now carries the wire id under its own type, callers return the client handle they already hold, and there is nothing to re-stamp. Delete the “codex/gemini/pi re-stamp … only claude …” prose.

- [ ] **Step 7: SPI + call-shape adaptations (compiler-led; the type change enumerates every site).**
  - `AgentBackend.scala`: `registerSession(client: SessionId[B], server: WireSessionId[B])`, `resumeWireId(client): Option[WireSessionId[B]]`; `probeServerSession` maps `Some(srv) => probeGuarded(srv.value)(probe)`.
  - `Agent.scala`: same two signature changes (defaults stay `None` / no-op in this task; Task 2 restructures them).
  - `BaseAgent.scala`: forwarders adapt; **`autonomous.run` returns `(session, result.output)`** — the caller-supplied client id, never the result's wire id. This deletes the claude exception.
  - `AgentCall.scala` `runInteractiveOnce`: `backend.registerSession(session, result.wireId)`; the trailing comment about “not `result.sessionId` which for codex is the server thread id” shrinks to: `// The stable client handle; result.wireId is the wire-side truth.`
  - `flow/src/main/scala/orca/Session.scala` `persistResumeWireId`: `wireId.value` still compiles (extension exists on the new type) — verify types only.
  - `runner/.../FlowLifecycle.scala` `rehydrateSessions`: `agent.registerResumeWireId(SessionId[B](record.id), WireSessionId[B](wireId))`.
- [ ] **Step 8: Backend modules (per module, compiler-led; enumerate leftovers with `grep -rn "\.copy(sessionId" claude codex gemini opencode pi`).**
  - **Delete every `.copy(sessionId = session)` re-stamp** (`CodexBackend.scala:108`, `GeminiBackend.scala:83`, `PiBackend.scala:70`) — `runAutonomous` returns `drainAndCommit(...)` directly.
  - **opencode**: replace the inline `drainAutonomous` + `sessions.commitSuccess(session, result.sessionId)` + `result.copy(...)` block (`OpencodeBackend.scala:~108-114`) with `Conversations.drainAndCommit("opencode", conv, session, sessions, events)`, preserving the surrounding `try`/`finally conv.cancel()`. (This is the divergence flagged in finding 2.2; it lands here naturally because the re-stamp it inlined no longer exists.)
  - **Conversation drivers** (`ClaudeConversation`, `CodexConversation`, `GeminiConversation`, `OpencodeConversation`, `PiConversation`): construct `AgentResult(wireId = WireSessionId(<raw server id string>), ...)`; pi uses `clientSession.onWire`.
  - **Args/spawn paths**: where a `Dispatch` wire id becomes a CLI flag, use `.value` (the opaque type makes each site a compile error until fixed).
- [ ] **Step 9: Test adaptations (compiler-led).** All constructions of `AgentResult(...)` in tests (`ClaudeBackendTest`, `CodexBackendTest`/`CodexConversationTest`, `GeminiBackendTest`/`GeminiConversationTest`, `OpencodeBackendTest`/`DefaultOpencodeToolTest`/`OpencodeFlowTest`/`ConversationRendererTest`, `PiBackendTest`/`PiConversationTest`, `DefaultAgentCallTest`, `ConversationsTest`, `SessionRegistryTest`, `RunSeededTest` stubs) switch to `wireId = WireSessionId(...)`/`.onWire`. Existing assertions that a returned session equals the client id are the regression net for the deleted re-stamps — they must still pass unchanged.
- [ ] **Step 10: Verify** — `sbt --client "scalafmtAll; compile; Test/compile; test"`. Expected: zero warnings, all green, including the new Step-1 test.
- [ ] **Step 11: Commit** — `refactor(sessions)!: split client SessionId from WireSessionId; drop per-backend re-stamps`.

---

### Task 2: `SessionSupport` — durability as one structural choice (findings 1.2 + 1.7)

**Files:**
- Create: `tools/src/main/scala/orca/backend/SessionSupport.scala`
- Modify: `tools/src/main/scala/orca/backend/AgentBackend.scala` (delete the 3 overridables + `probeGuarded`/`probeServerSession`; add `def sessions: SessionSupport[B]` and `def tag: B`— tag lands in Task 3, keep this task to `sessions`)
- Modify: `tools/src/main/scala/orca/agents/Agent.scala`, `BaseAgent.scala`, `AgentCall.scala`
- Modify: all five `*Backend.scala`
- Test: create `tools/src/test/scala/orca/backend/SessionSupportTest.scala`; adapt `ClaudeBackendTest`, `RunSeededTest` stubs, any test `AgentBackend` stubs

**Interfaces:**
- Produces:

```scala
enum SessionSupport[B <: BackendTag](val registry: SessionRegistry[B]):
  case Ephemeral[B <: BackendTag](r: SessionRegistry[B]) extends SessionSupport[B](r)
  case Durable[B <: BackendTag](r: SessionRegistry[B], probe: String => Boolean) extends SessionSupport[B](r)

  final def register(client: SessionId[B], server: WireSessionId[B]): Unit
  final def persistableWireId(client: SessionId[B]): Option[WireSessionId[B]]  // None for Ephemeral
  final def exists(client: SessionId[B]): Boolean                              // false for Ephemeral
```

- `AgentBackend.sessions: SessionSupport[B]` (abstract); `Agent.sessionSupport: Option[SessionSupport[B]] = None` (`private[orca]`, the ONLY overridable hook — `sessionExists`/`resumeWireId`/`registerResumeWireId` become `final` on `Agent`, implemented via it); `BaseAgent` overrides `sessionSupport = Some(backend.sessions)` and deletes its three forwarders.
- Consumes: Task 1 types.

- [ ] **Step 1: Failing tests** in `SessionSupportTest.scala`:

```scala
test("Ephemeral: never durable — exists false, nothing persistable, register still feeds in-run dispatch"):
  val reg = new SessionRegistry.ClaimedOnce[BackendTag.Pi.type]
  val s = SessionSupport.Ephemeral(reg)
  val id = SessionId.fresh[BackendTag.Pi.type]
  s.register(id, id.onWire)
  assert(!s.exists(id) && s.persistableWireId(id).isEmpty)
  assert(reg.dispatchFor(id).isInstanceOf[Dispatch.Resume[?]])

test("Durable: exists = registry mapping AND guarded probe"):
  val reg = new SessionRegistry.ClientToServer[BackendTag.Codex.type]
  var probed = List.empty[String]
  val s = SessionSupport.Durable(reg, id => { probed = id :: probed; id == "srv-1" })
  val client = SessionId.fresh[BackendTag.Codex.type]
  assert(!s.exists(client))                       // no mapping yet — probe not called
  s.register(client, WireSessionId("srv-1"))
  assert(s.exists(client) && probed == List("srv-1"))

test("Durable: unsafe wire id and throwing probe are both 'absent'"):
  val reg = new SessionRegistry.ClientToServer[BackendTag.Codex.type]
  val client = SessionId.fresh[BackendTag.Codex.type]
  reg.commitSuccess(client, WireSessionId("../etc"))
  assert(!SessionSupport.Durable(reg, _ => true).exists(client))
  val client2 = SessionId.fresh[BackendTag.Codex.type]
  reg.commitSuccess(client2, WireSessionId("ok-id"))
  assert(!SessionSupport.Durable(reg, _ => throw RuntimeException("boom")).exists(client2))
```

- [ ] **Step 2: Run — expect compile failure** (`SessionSupport` not found).
- [ ] **Step 3: Implement `SessionSupport.scala`** with the enum above; `exists` inlines the old `probeGuarded` contract (`isSafeSessionId` + `NonFatal` ⇒ false), `persistableWireId` matches `Durable => registry.resumeWireId(client)` / `Ephemeral => None`. Scaladoc states the design intent: *durability is the whole capability or none of it — a new backend cannot half-wire resume (the claude/codex shipped-bug class).*
- [ ] **Step 4: Collapse the SPI.**
  - `AgentBackend`: delete `registerSession`, `resumeWireId`, `sessionExists`, `probeGuarded`, `probeServerSession`; add abstract `def sessions: SessionSupport[B]`.
  - `Agent`: add `private[orca] def sessionSupport: Option[SessionSupport[B]] = None`; make the trio `final`:

```scala
final def sessionExists(session: SessionId[B]): Boolean =
  sessionSupport.exists(_.exists(session))
final def resumeWireId(client: SessionId[B]): Option[WireSessionId[B]] =
  sessionSupport.flatMap(_.persistableWireId(client))
final def registerResumeWireId(client: SessionId[B], wireId: WireSessionId[B]): Unit =
  sessionSupport.foreach(_.register(client, wireId))
```

  - `BaseAgent`: delete the three forwarders; add `override private[orca] def sessionSupport: Option[SessionSupport[B]] = Some(backend.sessions)`.
  - `AgentCall.runInteractiveOnce`: `backend.sessions.register(session, result.wireId)`.
- [ ] **Step 5: Backends declare their shape** (rename each private registry val `sessions` → `registry` first):
  - claude: `val sessions: SessionSupport[BackendTag.ClaudeCode.type] = SessionSupport.Durable(registry, id => os.exists(projectsDir / ClaudeBackend.cwdSlug(cwdForProbe) / s"$id.jsonl"))`; interactive commit becomes `registry.commitSuccess(session, session.onWire)`. **Behavioural note (document in the scaladoc + adapt `ClaudeBackendTest`):** `sessionExists` is now registry-gated — a transcript file alone no longer answers `true`; the id must also be claimed/rehydrated this run. The one divergent scenario (record without a persisted wire id, transcript present) failed before too — `dispatchFor` said `Fresh` and the CLI refused the duplicate `--session-id`; outcomes are identical, the gate just moves the `false` earlier.
  - codex: `SessionSupport.Durable(registry, id => os.exists(sessionsDir) && os.walk.stream(sessionsDir).exists(p => p.last.startsWith("rollout-") && p.last.endsWith(s"-$id.jsonl")))`.
  - gemini / opencode: `SessionSupport.Durable(registry, <existing probe lambda>)` (their probes already resolved via the registry — the shared `exists` now does that resolution).
  - pi: `SessionSupport.Ephemeral(registry)`; delete the `registerSession` override + the “No `resumeWireId` override” comment block (the enum case now says it).
- [ ] **Step 6: Test adaptations.** `ClaudeBackendTest` existence tests claim first (`backend.sessions.register(id, id.onWire)`) then probe; `RunSeededTest`/`SessionTest` agent stubs replace `override def sessionExists` with `override private[orca] def sessionSupport = Some(SessionSupport.Durable(stubRegistry, _ => <flag>))` (add a tiny helper in the test file); any stub `AgentBackend` gains `val sessions = SessionSupport.Ephemeral(new SessionRegistry.ClaimedOnce)`.
- [ ] **Step 7: Verify** — full format/compile/test. Expected green, zero warnings.
- [ ] **Step 8: Commit** — `refactor(sessions)!: SessionSupport makes backend durability one structural choice`.

---

### Task 3: Backend-tagged `SessionRecord` + targeted rehydration (finding 1.3)

**Files:**
- Modify: `tools/src/main/scala/orca/backend/AgentBackend.scala` (add `def tag: B`), all five `*Backend.scala` (one-line `val tag`), `tools/src/main/scala/orca/agents/Agent.scala` + `BaseAgent.scala` (`private[orca] def backendTag: Option[BackendTag]`)
- Modify: `flow/src/main/scala/orca/progress/ProgressLog.scala` (`SessionRecord.backend: Option[String] = None`), `flow/src/main/scala/orca/Session.scala` (write it)
- Modify: `runner/src/main/scala/orca/runner/FlowLifecycle.scala` (`rehydrateSessions(ctx: FlowContext, lead: Agent[?], store)`), `runner/src/main/scala/orca/flow.scala` (call site + delete the “known limitation” comment)
- Test: `runner/src/test/scala/orca/runner/FlowLifecycleTest.scala`, `flow/src/test/scala/orca/SessionTest.scala`, `flow/src/test/scala/orca/progress/ProgressLogTest.scala`

**Interfaces:**
- Produces: `AgentBackend.tag: B` (e.g. `val tag: BackendTag.Codex.type = BackendTag.Codex`); `Agent.backendTag: Option[BackendTag]` (`None` default, `Some(backend.tag)` in `BaseAgent`); `SessionRecord.backend: Option[String]` holding `BackendTag#toString`; rehydration resolves the target agent per record via the `FlowContext` accessors, falling back to the lead for untagged (older) records, skipping unknown tags.

- [ ] **Step 1: Failing test** in `FlowLifecycleTest` (reuse its existing stub context/agents; add a recording stub per backend accessor):

```scala
test("rehydrateSessions replays a codex-tagged record into the codex agent, not the lead"):
  val store = storeWith(sessions = List(
    SessionRecord(index = 0, id = "c-1", seed = "s", resumeWireId = Some("srv-9"), backend = Some("Codex"))
  ))
  val (lead, codex) = (recordingAgent[BackendTag.ClaudeCode.type](), recordingAgent[BackendTag.Codex.type]())
  FlowLifecycle.rehydrateSessions(ctxWith(codex = codex), lead, store)
  assert(lead.registered.isEmpty)
  assert(codex.registered == List("c-1" -> "srv-9"))

test("rehydrateSessions falls back to the lead for an untagged (older) record"):
  ...assert(lead.registered == List("old-1" -> "srv-1"))

test("rehydrateSessions skips a record with an unknown backend tag"):
  ...assert(lead.registered.isEmpty && codex.registered.isEmpty)
```

(Note: `index = 0` until Task 4 renames the keying; the executing agent adjusts if Task 4 landed first — it does not, order is fixed.)

- [ ] **Step 2: Run — expect failure** (no `backend` field / wrong `rehydrateSessions` arity).
- [ ] **Step 3: Implement.**
  - `AgentBackend`: `/** Runtime value of the compile-time tag `B`; lets the runtime record which backend a session belongs to. */ def tag: B`. Backends: `val tag: BackendTag.ClaudeCode.type = BackendTag.ClaudeCode` etc.
  - `Agent`: `private[orca] def backendTag: Option[BackendTag] = None`; `BaseAgent`: `Some(backend.tag)`.
  - `SessionRecord`: add `backend: Option[String] = None` (lenient codec already tolerates absence).
  - `Session.session`: record `backend = agent.backendTag.map(_.toString)`.
  - `FlowLifecycle`:

```scala
private[orca] def rehydrateSessions(
    ctx: FlowContext,
    lead: Agent[?],
    store: ProgressStore
): Unit =
  for
    log <- store.load().toList
    record <- log.sessions
    wireId <- record.resumeWireId
    agent <- targetAgent(ctx, lead, record.backend)
  do register(agent, record.id, wireId)

/** Untagged records (older logs) go to the lead — the pre-tagging behaviour.
  * A tag that matches no accessor (edited log) is skipped, not guessed.
  * Resolving an accessor may construct that backend's default agent; that is
  * correct — a record for backend X means the body will use X again anyway.
  */
private def targetAgent(
    ctx: FlowContext,
    lead: Agent[?],
    tag: Option[String]
): Option[Agent[?]] =
  tag match
    case None => Some(lead)
    case Some(t) =>
      BackendTag.values.find(_.toString == t).map:
        case BackendTag.ClaudeCode => ctx.claude
        case BackendTag.Codex      => ctx.codex
        case BackendTag.Opencode   => ctx.opencode
        case BackendTag.Pi         => ctx.pi
        case BackendTag.Gemini     => ctx.gemini

private def register[B <: BackendTag](agent: Agent[B], id: String, wire: String): Unit =
  agent.registerResumeWireId(SessionId[B](id), WireSessionId[B](wire))
```

  (`register(agent, ...)` with `agent: Agent[?]` — call it via `agent match case a: Agent[t] => register(a, record.id, wireId)` if inference balks; prefer the direct generic call first.)
  - `flow.scala`: `FlowLifecycle.rehydrateSessions(ctx, ctx.agent, store)`; rewrite the comment — targeted rehydration, no longer lead-only.
- [ ] **Step 4: Extend `SessionTest`** — a minted record carries the agent's tag (stub agent overrides `backendTag = Some(BackendTag.Codex)`); `ProgressLogTest` — round-trip with and without `backend` (older-log compat).
- [ ] **Step 5: Verify** — full format/compile/test.
- [ ] **Step 6: Commit** — `fix(sessions): tag session records with their backend; rehydrate per-backend, not lead-only`.

---

### Task 4: Named sessions (finding 1.4)

**Files:**
- Modify: `flow/src/main/scala/orca/FlowControl.scala` (`nextSessionOccurrence(name: String): Int`), `runner/src/main/scala/orca/runner/DefaultFlowContext.scala` + `flow/src/test/scala/orca/TestFlowContext.scala` (per-name counter, mirroring the stage counter)
- Modify: `flow/src/main/scala/orca/progress/ProgressLog.scala` (`SessionRecord`: `name: String = ""`, `occurrence: Int = 0` replace `index: Int`), `flow/src/main/scala/orca/progress/ProgressStore.scala` (upsert key = `(name, occurrence)`)
- Modify: `flow/src/main/scala/orca/Session.scala` (`session(name: String, seed: String)`)
- Modify: examples `implement.sc`, `implement-enhanced.sc`, `implement-interactive.sc`, `epic.sc`, `issue-pr.sc`, `issue-pr-bugfix.sc`; `runner/src/test/scala/flowtests/FlowCompilesTest.scala`
- Test: `flow/src/test/scala/orca/SessionTest.scala`, `flow/src/test/scala/orca/progress/ProgressStoreTest.scala`, `ProgressLogTest.scala`, `RunSeededTest.scala`

**Interfaces:**
- Produces: `agent.session(name: String, seed: String)(using FlowControl): SessionId[B]` keyed by `name` + occurrence (stage-style: inserting/reordering *other* sessions no longer re-keys this one); `SessionRecord(name, occurrence, id, seed, resumeWireId, backend)`.
- **Breaking change, clean break:** the positional `session(seed)` overload is deleted; all in-repo callers updated in this task.

- [ ] **Step 1: Failing tests** — rewrite `SessionTest` around names:

```scala
test("same name+occurrence resumes the recorded id across runs"):
  val id1 = agent.session("implementer", "brief")(using fc1)
  val id2 = agent.session("implementer", "brief")(using fc2)  // fresh control, same store
  assert(id1 == id2)

test("an unrelated session inserted before does not re-key a named session"):
  // run 1: only "implementer"; run 2: "planner" first, then "implementer"
  ...assert(implementerRun2 == implementerRun1)

test("two calls with the same name get distinct occurrences"):
  val a = agent.session("reviewer", "s1")(using fc)
  val b = agent.session("reviewer", "s2")(using fc)
  assert(a != b)
```

  Keep the seed-mismatch-warning test, re-phrased for name keying (“recorded seed differs for this name — the seed was edited; reusing the recorded session”).
- [ ] **Step 2: Run — expect compile failures** at every `session(` call site.
- [ ] **Step 3: Implement.**
  - `FlowControl`: `def nextSessionOccurrence(name: String): Int` with scaladoc mirroring `nextOccurrence` (0 for the first `session(name)`, 1 for the second; independent of the stage counter).
  - `DefaultFlowContext`/`TestFlowContext`: replace the single session counter with a per-name immutable-`Map[String, Int]`-in-`var` counter, exactly like the stage one (they are single-threaded by R12; match the existing implementation style at each site).
  - `SessionRecord`: `case class SessionRecord(name: String = "", occurrence: Int = 0, id: String, seed: String, resumeWireId: Option[String] = None, backend: Option[String] = None)` — defaults keep older logs decodable (they degrade to `("", 0)` and simply re-seed; the log is a per-run artifact, cross-version resume of an in-flight run is explicitly not supported — say so in the scaladoc).
  - `ProgressStore.upsertSession`: key by `record.name` + `record.occurrence`.
  - `Session.session(name, seed)`: occurrence from `fc.nextSessionOccurrence(name)`; lookup `_.sessions.find(r => r.name == name && r.occurrence == occ)`; scaladoc drops the “stable order and unconditional” authoring rule — identity now survives insertion/reordering of other sessions; only same-name call-order matters for duplicates.
- [ ] **Step 4: Update callers** — examples: `agent.session("implementer", seed = plan.brief)` (implement/implement-enhanced/epic), `claude.session("fixer", seed = issue.body)` (issue-pr-bugfix), and whatever `implement-interactive.sc`/`issue-pr.sc` use (name by role); `FlowCompilesTest` mirrors one of them; `RunSeededTest` setup uses the new signature.
- [ ] **Step 5: Verify** — full format/compile/test, plus `sbt --client "runner/testOnly flowtests.FlowCompilesTest"` explicitly (the examples compile-canary).
- [ ] **Step 6: Commit** — `feat(sessions)!: name-based session identity (session(name, seed)), stage-style keying`.

---

### Task 5: Durability contract docs + AGENTS.md + tracker (finding 1.5; closes 1.1–1.7)

**Files:**
- Modify: `flow/src/main/scala/orca/progress/ProgressStore.scala` (`upsertSession` scaladoc), `flow/src/main/scala/orca/Session.scala` (`session` scaladoc), `AGENTS.md` (Sessions section), `adr/0018-stage-bound-flow-runtime.md` (amendment note in §2.6), `complexity-review.md`

- [ ] **Step 1: Document the reset-hard interaction** (decision: document, don't commit-per-mint — a commit per session pollutes history and the loss mode is fail-safe). On `ProgressStore.upsertSession` extend the existing “Does NOT commit” note with: *“Consequence: on failure teardown (`git reset --hard`) any session record written since the last stage commit is erased — the retry then mints a fresh session and re-seeds. This is the intended fail-safe; `session(name, seed)`'s get-or-create contract is therefore best-effort until a stage commit has carried the log.”* Mirror one sentence on `Session.session`.
- [ ] **Step 2: Rewrite AGENTS.md “Sessions”** — replace the four-method pairing prose (and the shipped-bugs warning as a *current* hazard) with the `SessionSupport` model: one structural choice per backend; named session identity; tagged rehydration. Keep the bug history as a one-line rationale.
- [ ] **Step 3: ADR 0018 §2.6 amendment note** — dated note (2026-07-06): sessions are now name-keyed (`session(name, seed)`), records backend-tagged, durability declared via `SessionSupport`; supersedes the positional-keying and lead-only-rehydration text.
- [ ] **Step 4: Tick the tracker** — `complexity-review.md`: mark 1.1–1.7 `[x]` with the task commit hashes; add a note on 2.2 that the opencode drain divergence was absorbed by 1.1's task (leave 2.2 unticked — the shared-shell hoist itself remains open).
- [ ] **Step 5: Verify** — `sbt --client "scalafmtAll; compile; test"` (docs-only, but keep the gate).
- [ ] **Step 6: Commit** — `docs(sessions): durability contract, AGENTS.md/ADR-0018 updates; tick review findings 1.1–1.7`.

---

### Task 6: Multi-agent code review of the whole change set

- [ ] **Step 1:** `git diff master...HEAD` scope; dispatch in parallel: **code-functionality-reviewer**, **scala-fp-reviewer**, **simplicity-reviewer**, **test-reviewer** on the branch diff (per the user's request to review with the available review agents).
- [ ] **Step 2:** Triage findings with the superpowers:receiving-code-review discipline (verify before implementing; push back with evidence where a finding is wrong).
- [ ] **Step 3:** Apply accepted fixes, re-run full verification, commit — `fix(sessions): address review findings on the session-protocol refactor`.
