# Cross-Backend Divergence Fixes (findings 2.1–2.5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the five coding-agent backends one auditable home for their enforcement semantics, one shared autonomous-run shell, one pinned conversation-event grammar, one ask_user timeout story, and no more TypeScript-in-a-Scala-string.

**Architecture:** Five bounded refactors of the backend seam, in risk order: hoist the drain shell (pure refactor) → enforcement semantics as data + honest claude `Only` mapping (behavior fix) → event-grammar contract with `Option[String]` tool names + driver normalization → gemini MCP timeout → pi extension as a classpath resource. Docs/tracker + a 4-agent review wave close it out.

**Tech Stack:** Scala 3 (braceless), Ox, jsoniter-scala, sbt multi-module (`tools` ← `flow`/`claude`/`codex`/`gemini`/`opencode`/`pi` ← `runner`; every backend module and `runner` has `tools % "test->test"`).

## Global Constraints

- Zero compile warnings (`-Wunused:all`, `-Wvalue-discard`, `-Wnonunit-statement`); braceless Scala 3; explicit return types on public members.
- `sbt --client` for loops; `sbt --client scalafmtAll` before every commit; full gate per task: `sbt --client "scalafmtAll; compile; Test/compile; test"`.
- Commits end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Branch: continue on `session-identity-fixes` (already pushed; the 2.x work stacks on the 1.x work).
- User decisions (binding): claude `AutoApprove.Only` gets HONEST semantics (default permission mode + `--allowedTools`; edits auto-approved only if listed; `Only(empty)` → everything prompts). Event grammar gets contract + driver normalization, and `ToolResult.toolName` becomes `Option[String]` (no empty-string sentinel).
- Tracker: `/home/adamw/orca/complexity-review.md` items 2.1–2.5 ticked in the final task.

---

### Task 1: Hoist the autonomous run shell (finding 2.2)

**Files:**
- Modify: `tools/src/main/scala/orca/backend/Conversations.scala`
- Modify: `claude/src/main/scala/orca/tools/claude/ClaudeBackend.scala` (runAutonomous), `codex/.../CodexBackend.scala`, `gemini/.../GeminiBackend.scala`, `opencode/.../OpencodeBackend.scala`, `pi/.../PiBackend.scala`
- Test: `tools/src/test/scala/orca/backend/ConversationsTest.scala`

**Interfaces:**
- Produces (in `Conversations`):

```scala
/** The complete autonomous-turn shell shared by all backends: open the
  * conversation inside its own supervised scope, drain + commit, and ALWAYS
  * cancel before the scope joins (the cancel is load-bearing on failure paths —
  * `drainAndCommit` does not tear down). `open` runs inside the scope so the
  * conversation's forks bind to it.
  */
def runAutonomous[B <: BackendTag](
    backendName: String,
    session: SessionId[B],
    registry: SessionRegistry[B],
    events: OrcaListener
)(open: Ox ?=> Conversation[B]): AgentResult[B] =
  supervised:
    val conv = open
    try drainAndCommit(backendName, conv, session, registry, events)
    finally conv.cancel()
```

- [ ] **Step 1: Failing test** in `ConversationsTest.scala` (reuse its stub conversation/registry helpers):

```scala
test("runAutonomous drains, commits, and cancels even on failure"):
  // success path: result returned, registry committed, cancel() invoked once
  // failure path: a conversation whose drain throws AgentTurnFailed still gets cancel()
```
Write both as real tests against a recording stub `Conversation` (track `cancel()` calls). RED: `runAutonomous` not found.

- [ ] **Step 2: Implement** the method above in `Conversations` (needs `import ox.{Ox, supervised}`).
- [ ] **Step 3: Collapse the five backends.** Each `runAutonomous` becomes a single expression, e.g. gemini:

```scala
def runAutonomous(...): AgentResult[BackendTag.Gemini.type] =
  Conversations.runAutonomous("gemini", session, registry, events):
    openConversation(prompt, SessionMode.Autonomous, session, config, workDir, outputSchema)
```

opencode keeps its `val http = server(workDir)` line BEFORE the call and creates the SSE source inside the thunk (`openConversation(http, http.events(), serverSessionFor(http, session), ...)`) so the reader forks bind to the shell's scope. PRESERVE each backend's per-call comments that still apply (e.g. codex's output-schema note) by moving them onto the collapsed call or the openConversation site; DELETE the now-centralized "Self-scoped… finally is load-bearing" boilerplate comments (the shell's scaladoc owns that contract now).
- [ ] **Step 4: Verify** full gate; backend behavior tests must pass unchanged (they pin the shell's observable behavior).
- [ ] **Step 5: Commit** — `refactor(backend): hoist the shared autonomous run shell into Conversations.runAutonomous`.

---

### Task 2: Enforcement semantics as data + honest claude `Only` (finding 2.1)

**Files:**
- Modify: `tools/src/main/scala/orca/agents/AgentConfig.scala` (add `Enforcement` enum next to `ToolSet`)
- Modify: `tools/src/main/scala/orca/backend/AgentBackend.scala` (abstract `def enforcement(tools: ToolSet, autoApprove: AutoApprove): Enforcement`)
- Modify: all five `*Backend.scala` (implement it, delegating to a function in each module's `*Args.scala` so the mapping sits beside the flag-building it describes)
- Modify: `claude/src/main/scala/orca/tools/claude/ClaudeArgs.scala` (honest `Only`)
- Create: `runner/src/test/scala/orca/runner/EnforcementTableTest.scala` (the single machine-checked matrix)
- Modify: `AGENTS.md` (rendered table), `claude/src/test/scala/orca/tools/claude/ClaudeArgsTest.scala`
- Test: the new table test + updated ClaudeArgsTest

**Interfaces:**
- Produces, in `AgentConfig.scala`:

```scala
/** How strongly a backend enforces the restriction a `(ToolSet, AutoApprove)`
  * combination requests. For the read-only tiers the restriction is "no
  * edits/shell"; for `Full` it is the approval policy itself.
  *
  *   - Hard — mechanically blocked (permission mode, sandbox, tool allowlist).
  *   - SandboxApprox — approximated by a coarser sandbox; semantics widened.
  *   - PromptOnly — only the prompt forbids it; the tools can physically do it.
  *   - Ignored — not encoded at all; actual behavior depends on backend/server
  *     configuration outside orca's control.
  */
enum Enforcement:
  case Hard, SandboxApprox, PromptOnly, Ignored
```

- `AgentBackend`: `def enforcement(tools: ToolSet, autoApprove: AutoApprove): Enforcement` (abstract, with scaladoc pointing at the runner-level table test as the source of truth). Each backend: `def enforcement(...) = <Module>Args.enforcement(tools, autoApprove)`.

**The matrix (binding — this is the researched truth of each arg builder, post claude fix):**

| tools, approve | claude | codex | gemini | opencode | pi |
|---|---|---|---|---|---|
| ReadOnly, * | Hard | Hard | Hard | Hard | Hard |
| NetworkOnly, * | Hard | PromptOnly | Hard | Hard | PromptOnly |
| Full, All | Hard | Hard | Hard | Ignored | Ignored |
| Full, Only(_) | Hard | SandboxApprox | Ignored | Ignored | Ignored |

Rationale per cell (goes into each `*Args.enforcement` scaladoc): claude plan-mode/allowlists are mechanical; codex NetworkOnly needs workspace-write (prompt-only no-edit) and Only(_) ≈ `--full-auto`; gemini plan-mode is hard but Full widens any `Only` to `yolo` (ADR 0015); opencode read-only tiers disable write tools (hard) but approval policy is whatever the user's server config says (ADR 0014 risk — `Ignored` for both All and Only); pi's `--tools` allowlist is hard for ReadOnly, NetworkOnly adds writable `bash` (prompt-only), and pi RPC never prompts and encodes no approval config (`Ignored`).

- [ ] **Step 1: Failing table test** — `runner/src/test/scala/orca/runner/EnforcementTableTest.scala`: instantiate the five backends the cheap way (each module's backend class is `private[orca]`, and runner is inside `orca` — construct e.g. `ClaudeBackend(cli)` with a stub `CliRunner`; check how existing runner tests / each module's tests construct backends and reuse the simplest pattern; if direct construction drags heavy deps for opencode, its `enforcement` must not touch the server — keep the method a pure function so construction stays cheap). Assert every cell of the matrix above with one `assertEquals` per cell, table-driven:

```scala
val cases: List[(String, AgentBackend[?], ToolSet, AutoApprove, Enforcement)] = ...
for (name, backend, tools, approve, expected) <- cases do
  assertEquals(backend.enforcement(tools, approve), expected, s"$name/$tools/$approve")
```
RED: `Enforcement` not found.
- [ ] **Step 2: Implement** `Enforcement`, the SPI method, and the five `*Args.enforcement` functions (pattern matches mirroring each builder's structure, one scaladoc line per cell explaining WHY, cross-referencing the builder function).
- [ ] **Step 3: Honest claude `Only`** — in `ClaudeArgs.autoApproveArgs` replace the `Full` branch:

```scala
case ToolSet.Full =>
  config.autoApprove match
    case AutoApprove.All => Seq("--permission-mode", "bypassPermissions")
    // Honest Only semantics: default permission mode — nothing is pre-approved
    // except the listed tools. Edits are NOT implicitly approved (they were,
    // via acceptEdits, before complexity-review finding 2.1); in autonomous
    // mode an unlisted tool's prompt is auto-denied by the drain.
    case AutoApprove.Only(tools) if tools.isEmpty => Seq.empty
    case AutoApprove.Only(tools) =>
      Seq("--allowedTools", tools.toSeq.sorted.mkString(","))
```
Update `ClaudeArgsTest` expectations (the acceptEdits assertions become default-mode/allowlist assertions; add an `Only(empty) → no permission flags` case). Update the `autoApproveArgs` scaladoc.
- [ ] **Step 4: AGENTS.md** — add the rendered matrix as a table in the backends section, one sentence pointing at `EnforcementTableTest` as the machine-checked source.
- [ ] **Step 5: Verify** full gate. **Step 6: Commit** — `feat(backend)!: surface per-backend enforcement semantics; honest AutoApprove.Only on claude`.

---

### Task 3: ConversationEvent grammar — contract, Option toolName, normalization (finding 2.3)

**Files:**
- Modify: `tools/src/main/scala/orca/backend/ConversationEvent.scala` (contract scaladoc + `ToolResult(toolName: Option[String], ok: Boolean, content: String)`)
- Create: `tools/src/test/scala/orca/backend/ConversationEventConformance.scala` (shared assertion helper — visible to all backend tests via `tools % "test->test"`)
- Modify: the five `*Conversation.scala` drivers (Option construction; normalization below), `runner/.../terminal/ConversationRenderer.scala` (pattern adapts), plus every test constructing `ToolResult(...)`
- Test: conformance assertions added to each backend's existing scripted conversation tests

**The contract (goes verbatim-ish into the `ConversationEvent` scaladoc):**
- A *turn* starts at the first assistant activity (`AssistantTextDelta` / `AssistantThinkingDelta` / `AssistantToolCall`) after the stream start or the previous `AssistantTurnEnd`.
- Every turn that the wire *completed* (the backend reported turn end, or the conversation settled — success or failure) is terminated by exactly one `AssistantTurnEnd`. A missing trailing `AssistantTurnEnd` is legal only when the stream terminates abnormally mid-turn; consumers must flush at end-of-stream (as `Conversations.drainAutonomous` does).
- `AssistantTurnEnd` never fires without assistant activity since the last one (no empty turns).
- `ToolResult.toolName` is `Some(name)` when the wire carries the name, `None` when it doesn't (claude's tool_result blocks carry only an id). Never `Some("")`.

**Driver normalizations (from the reviewed divergences):**
- opencode (`OpencodeConversation.scala:143-156`): `AssistantTurnEnd` currently emitted only on the success branch — emit it on the failure settle too when a turn is open (assistant activity since last TurnEnd). Track with the driver's existing turn state or a small `var activitySinceTurnEnd: Boolean` (single reader thread).
- pi (`PiConversation.scala:59,165-167`): the `sawAssistantMessage` gate misses tool-call-only turns — widen the gate to any assistant activity (message OR tool call).
- gemini/codex/claude: verify compliant (gemini's TurnEnd-on-failure is *correct* under the contract); adjust only if a scripted test proves a violation.
- claude `ClaudeConversation.scala:158-165`: `ToolResult` gets `toolName = None` (drop the `""`).

- [ ] **Step 1: Conformance helper (write first)** — `ConversationEventConformance.scala` in tools TEST sources:

```scala
package orca.backend

/** Asserts the ConversationEvent grammar (see ConversationEvent's scaladoc)
  * over a recorded event sequence. Shared by every backend's scripted
  * conversation tests via `tools % "test->test"`.
  */
object ConversationEventConformance:
  /** completedNormally = the scripted scenario settled (success or failure),
    * as opposed to an abnormal mid-turn kill.
    */
  def assertGrammar(events: List[ConversationEvent], completedNormally: Boolean): Unit =
    // walk the sequence tracking activity-since-last-TurnEnd:
    //  - TurnEnd with no prior activity => fail("empty turn")
    //  - ToolResult(Some(""), _, _) => fail("empty toolName must be None")
    //  - completedNormally && trailing activity without TurnEnd => fail("unterminated turn")
```
Implement fully (plain fold over the list, munit-style asserts matching the test framework used in tools tests).
- [ ] **Step 2: Retype + contract.** `ToolResult(toolName: Option[String], ok: Boolean, content: String)`; write the contract scaladoc on the enum; compiler-led adaptation of drivers, renderer, and tests (constructions become `Some(name)`/`None`).
- [ ] **Step 3: Normalize opencode + pi** per the list above; wire `assertGrammar` into each backend's existing scripted conversation tests (at minimum: one success scenario and one failure scenario per backend — reuse the scripts those tests already have; pass `completedNormally = true` for settled scenarios). Add the opencode failure-path TurnEnd test and the pi tool-call-only-turn test explicitly if no existing script covers them.
- [ ] **Step 4: Verify** full gate. **Step 5: Commit** — `feat(backend)!: pin the ConversationEvent grammar; Option toolName; normalize opencode/pi turn ends`.

---

### Task 4: Gemini ask_user timeout (finding 2.4)

**Files:**
- Modify: `gemini/src/main/scala/orca/tools/gemini/GeminiSettings.scala`, `claude/src/main/scala/orca/tools/claude/ClaudeBackend.scala` (comment only), `codex/src/main/scala/orca/tools/codex/CodexArgs.scala` (comment only)
- Test: `gemini/src/test/scala/orca/tools/gemini/GeminiSettingsTest.scala` (or wherever `merge` is tested — grep)

- [ ] **Step 1: Verify the field name** against gemini-cli's MCP docs (context7 `query-docs` for gemini-cli `settings.json` `mcpServers` — expected: per-server `timeout` in milliseconds, default 600 000). Record what you find in the commit message if it differs.
- [ ] **Step 2: Failing test** — extend the `merge` test: the orca entry must be `{"httpUrl": <url>, "timeout": 3600000}` (i.e. `AskUserMcpServer.ToolTimeout.toMillis`).
- [ ] **Step 3: Implement** — in `GeminiSettings.merge`, the orca entry becomes a two-field object; `Map("httpUrl" -> ..., "timeout" -> ...)` needs a heterogeneous value — build the entry with an explicit small codec or hand-assemble via the existing `RawJson` pattern:

```scala
val orcaEntry = RawJson(
  s"""{"httpUrl":${writeToString(mcpUrl)(using strCodec)},"timeout":${AskUserMcpServer.ToolTimeout.toMillis}}"""
)
```
(keep the codec-serialized URL — do not interpolate it raw; add `private given strCodec: JsonValueCodec[String]` if needed). Scaladoc: without it gemini's MCP default (10 min) undercuts the 1h `ToolTimeout` the other backends honor — the same answer-twice bug class, just with a longer fuse.
- [ ] **Step 4: Cross-reference comments** — one line at the claude JSON site (`ClaudeBackend` mcp config) and the codex TOML site (`CodexArgs.mcpServerArgs`): "one of three renderings of `AskUserMcpServer.ToolTimeout` — claude JSON ms / codex TOML sec / gemini settings.json ms; keep in sync."
- [ ] **Step 5: Verify** full gate. **Step 6: Commit** — `fix(gemini): give the ask_user MCP server the shared ToolTimeout in settings.json`.

---

### Task 5: Pi ask_user extension as a classpath resource (finding 2.5)

**Files:**
- Create: `pi/src/main/resources/orca/tools/pi/ask-user.ts` (the TypeScript, with `__TOOL_NAME__` placeholders where `$ToolName` was interpolated)
- Modify: `pi/src/main/scala/orca/tools/pi/PiAskUserExtension.scala`
- Test: `pi/src/test/scala/orca/tools/pi/` (extend whichever test covers `allocate()` — grep; else a new small `PiAskUserExtensionTest.scala`)

- [ ] **Step 1: Failing test** — `allocate()` writes a file that (a) contains `registerTool` and `name: "ask_user"`, (b) contains NO `__TOOL_NAME__` leftovers, (c) round-trips the resource (non-empty).
- [ ] **Step 2: Move the source.** The resource is the current `Source` string with every interpolated `$ToolName` replaced by `__TOOL_NAME__` (4 occurrences: name field + hint text usages inside guidelines). `PiAskUserExtension.allocate()` loads it:

```scala
private def loadSource(): String =
  val stream = getClass.getResourceAsStream("/orca/tools/pi/ask-user.ts")
  require(stream != null, "ask-user.ts resource missing from the pi module jar")
  try String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
  finally stream.close()

def allocate(): PiAskUserExtension =
  val dir = os.temp.dir(prefix = "orca-pi-ask-user-", deleteOnExit = true)
  val file = dir / "ask-user.ts"
  os.write(file, loadSource().replace("__TOOL_NAME__", ToolName))
  new PiAskUserExtension(dir, file)
```
Delete the `Source` string val. Keep `ToolName`/`Hint` in Scala (they're consumed elsewhere).
- [ ] **Step 3: Verify** full gate (pi integration test self-skips without the CLI; the unit test covers the substitution). **Step 4: Commit** — `refactor(pi): move the ask_user extension source to a classpath resource`.

---

### Task 6: Tracker + docs close-out, then the review wave

- [ ] **Step 1:** `complexity-review.md`: tick 2.1–2.5 with their commit hashes (2.2 keeps its earlier note; its checkbox now ticks). AGENTS.md: confirm the enforcement table landed (Task 2) and add one line on the event-grammar contract location. Commit — `docs: tick complexity findings 2.1-2.5`.
- [ ] **Step 2:** Whole-2.x-range review package; dispatch in parallel: **code-functionality-reviewer** (most capable model), **scala-fp-reviewer**, **simplicity-reviewer**, **test-reviewer** — scoped to the 2.x commits, with the enforcement matrix and grammar contract stated as the spec.
- [ ] **Step 3:** Triage findings (receiving-code-review discipline), ONE batch fix subagent for accepted findings, re-verify, commit — `fix(backend): address review findings on the divergence fixes`.
