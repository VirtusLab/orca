# Configurable Role Agents Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `flow(...)`'s mandatory lead-agent selector with three settings-configurable role agents — `planningAgent`, `codingAgent`, `reviewAgent` — resolved from `settings.properties` (user-global + per-project), defaulting to claude, while keeping each role's `Agent[B]` backend-typed.

**Architecture:** The existing `.orca/settings.properties` parser grows three single-valued keys (`planningAgent = <harness>[:<model>]`); a new user-global file at `$XDG_CONFIG_HOME/orca/settings.properties` carries the same agent keys (agent keys only). The runner resolves each role against the run's `WiredAgents` (so role agents share the wired backends' event sink and close lifecycle), applies an optional model pin via the harness's `withModel`, and opens the three runtime-selected existentials into `DefaultFlowContext[PB, CB, RB]` via type-variable patterns. `FlowContext.LeadB`/`agent` are replaced by three type members (`PlanB`/`CodeB`/`ReviewB`) and accessors, so sessions still thread with full backend typing.

**Tech Stack:** Scala 3.8.4 (braceless), sbt multi-module (`tools` → `flow`/backends → `runner`), munit, os-lib. No new dependencies.

## Global Constraints

- The project MUST compile with zero warnings (`-Wunused:all`, `-Wvalue-discard`, `-Wnonunit-statement`).
- Braceless Scala 3 syntax only; explicit return types on every public def/val/given.
- Format before every commit: `sbt --client scalafmtAll`.
- Compile/test via `sbt --client` (`compile`, `flow/test`, `runner/test`, …). Full-project `sbt` test runs take minutes — when dispatching subagents that run sbt, background them (repo convention).
- Consumers are version-pinned scripts: breaking the public API (`flow` signature, `agent` accessor removal) is deliberate and accepted; do NOT add deprecated aliases or compat shims.
- Threat model: agents are trusted-but-fallible; never justify a design detail by adversarial agent behaviour.
- Comment style: present-tense facts; no Scala-mechanics tutorials, no references to this plan or to "old"/"new" behaviour.

## Design decisions (the spec)

Decisions made during design research — each task below implements these verbatim:

1. **File format.** New keys in the existing strict line format, single-valued (a repeated agent key is an error, unlike the appending stack keys):
   ```properties
   planningAgent = claude:opus
   codingAgent = codex
   reviewAgent = opencode:anthropic/claude-sonnet-4-5
   ```
   Value grammar: `<harness>[:<model>]`, split at the FIRST `:`. Harness ∈ `claude | codex | opencode | pi | gemini` (error naming the valid set otherwise). The model part is passed **verbatim** to the harness's `withModel` — orca does not normalise or validate model ids (consistent with `Model`'s existing contract); for claude the CLI itself accepts aliases (`opus`, `sonnet`), for opencode it is the `provider/model` form its existing validation checks. An empty value ≡ key omitted (existing parser rule).
2. **Locations & precedence, per role key:** `flow(...)` programmatic override > `{workDir}/.orca/settings.properties` > `$XDG_CONFIG_HOME/orca/settings.properties` (default `~/.config/orca/settings.properties`, per the XDG Base Directory spec — the `gh`/`git` CLI convention, used on macOS too) > built-in default **claude, default model**. The global file may contain ONLY agent keys — stack commands (`format`/`lint`/`test`) are per-project by nature, and a stack key there is an error. A relative or unset `XDG_CONFIG_HOME` falls back to `~/.config` (per spec, relative values are ignored). An absent global file is simply skipped.
3. **Type safety.** `FlowContext` gets three abstract type members `PlanB, CodeB, ReviewB <: BackendTag` and accessors `planningAgent: Agent[PlanB]`, `codingAgent: Agent[CodeB]`, `reviewAgent: Agent[ReviewB]`, replacing `LeadB`/`agent`. The runner opens the three runtime-resolved `Agent[?]` values with type-variable patterns (`case p: Agent[pb] =>`) into `DefaultFlowContext[PB, CB, RB]`. Helpers keep their existing `[B <: BackendTag]` shape and unify path-dependently — same mechanism as today's `LeadB`. (Alternatives rejected: `Agent[?]` accessors lose session threading; keeping the static `flow[B]` selector is impossible because the backend is only known at runtime.)
4. **`flow(...)` signature.** The `agent` selector parameter and the `[B]` type parameter are removed. Three optional per-role overrides are added, selector-shaped so derived siblings stay expressible (`Some(_.claude.opus.withNetworkTools(...))`): `planningAgent: Option[AgentSet => Agent[?]] = None`, `codingAgent: ...`, `reviewAgent: ...`. These are also the test seam (tests never read the developer's real `~/.config`).
5. **Role mapping inside the library.** `codingAgent` is the run's primary: branch naming, stack discovery, stage default commit messages (`fc.codingAgent.cheapOneShot`), untagged session-record rehydration, and the implementer session in scripts. `reviewAgent` drives the review machinery defaults: `ReviewerSelector.agentDriven` picker and the `Lint` summariser (`ctx.reviewAgent.cheap`), and scripts build `allReviewers(reviewAgent)`. `planningAgent` is script-side only (`Plan.autonomous.from(userPrompt, planningAgent)`). Cheap/simple work uses each role's `.cheap` tier.
6. **Settings read sequencing.** Both files are parsed once, in `runFlow`, before `FlowLifecycle.setup` (still before `ensureClean`, so a malformed file aborts with no tree mutation and a stash cannot hide a hand-written file). The parsed project result carries both families; `setup` receives the pre-parsed stack resolution instead of re-reading the file. When `flow(stackSettings = Some(...))` is passed, the stack portion of the project file is ignored and discovery is skipped (unchanged), but agent keys in the file are STILL honoured — the override governs stack commands only. Consequence: a malformed project file now aborts even under a stack override (stricter than today; documented). A malformed GLOBAL file aborts the same way (a silently ignored config file would be worse than a loud abort).
7. **Discovery trigger becomes stack-aware.** ADR 0019's "file exists ⇒ discovery already ran" no longer holds once the file can carry agent keys: a hand-written file containing only `codingAgent = codex` must not silently disable every stack gate. New trigger: discovery runs when the file is absent OR when `SettingsFile.hasStackLines(content)` is false — i.e. no line, live or `#`-commented, names a stack key. Discovery-written files always contain at least commented stack lines (`# format =   (reason)` for unset tasks), so the frozen-file semantics of ADR 0019 are preserved exactly; only the genuinely stack-silent hand-written file re-triggers. When the file already exists (agents-only), the discovery write APPENDS the rendered stack entries to the existing content (no header duplication, agent lines untouched) instead of overwriting; the dedicated `orca: stack settings (discovered)` commit is unchanged.
8. **Sessions across config changes.** A recorded session whose backend tag no longer matches its role's backend mints fresh with a warning — `Session.scala`'s existing lead-swap arm already implements this; no new code, but it is now reachable via settings edits, so the README documents it.
9. **`CanAskUser` is dropped.** All five backends implement `ask_user` (claude/codex/gemini via the shared MCP server, opencode via its native `question` tool, pi via the extension), so the typeclass excludes nothing — and the raw `agent.resultAs[O].interactive` door never carried the bound. Deleting it lets `Plan.interactive.from(userPrompt, planningAgent)` compile against the abstract `ctx.PlanB`, making interactive planning role-configurable too. The runtime `Conversation.canAskUser` flag (the actual SPI wiring) stays. If a future backend genuinely lacks `ask_user`, reintroduce a constraint then — YAGNI now.
10. **Resolved roles are announced.** The lead selector used to be visible in the script; role selection is now ambient config, so setup emits one `Step` event naming each resolved role and its source, e.g. `agents: planning=claude (default), coding=codex:gpt-5-mini (project), review=claude (global)` — the debugging handle for "why did codex run here?".
11. **Non-goals** (record in ADR): per-role cheap-model configuration (`withCheapModel` stays programmatic), CLI flags for agent selection, named presets, Windows paths beyond the existing bash contract.

**File structure (whole feature):**

| File | Change |
|---|---|
| `flow/src/main/scala/orca/settings/AgentSpec.scala` | NEW — `AgentRole`, `AgentSpec` (+ parse), `AgentSettings` (+ merge) |
| `flow/src/main/scala/orca/settings/SettingsFile.scala` | new keys, scope-aware parse returning `ParsedSettings`, new errors, header text, `hasStackLines` + `renderAppend` |
| `flow/src/main/scala/orca/settings/GlobalSettings.scala` | NEW — XDG path resolution |
| `flow/src/main/scala/orca/FlowContext.scala` | three role type members + accessors; drop `LeadB`/`agent` (task 5) |
| `flow/src/main/scala/orca/accessors.scala` | three role accessors; drop `agent` (task 5) |
| `flow/src/main/scala/orca/Flow.scala` | commit-message default → `fc.codingAgent` |
| `flow/src/main/scala/orca/review/ReviewLoop.scala`, `ReviewerSelector.scala` | defaults → `ctx.reviewAgent.cheap` |
| `tools/src/main/scala/orca/agents/CanAskUser.scala` | DELETED — vacuous capability (all five backends have it) |
| `flow/src/main/scala/orca/plan/Plan.scala` | drop the four `: CanAskUser` bounds + scaladoc mentions |
| `gemini/src/test/scala/orca/tools/gemini/GeminiCanAskUserTest.scala` | DELETED with the typeclass |
| `runner/src/main/scala/orca/runner/RoleAgents.scala` | NEW — settings → wired-agent resolution |
| `runner/src/main/scala/orca/flow.scala` | drop selector/`[B]`, add role overrides, read settings, open existentials |
| `runner/src/main/scala/orca/runner/DefaultFlowContext.scala` | `[PB, CB, RB]`, three agent vals |
| `runner/src/main/scala/orca/runner/FlowLifecycle.scala` | take pre-parsed settings; `codingAgent` as setup/rehydration agent |
| `examples/*.sc`, `README.md`, `AGENTS.md`, `adr/0020-*.md` | docs & examples |

---

### Task 1: Settings model — `AgentSpec`, new keys, scope-aware parser

**Files:**
- Create: `flow/src/main/scala/orca/settings/AgentSpec.scala`
- Modify: `flow/src/main/scala/orca/settings/SettingsFile.scala`
- Test: `flow/src/test/scala/orca/settings/SettingsFileTest.scala` (extend), `flow/src/test/scala/orca/settings/AgentSpecTest.scala` (new)

**Interfaces:**
- Consumes: `orca.agents.BackendTag`, `orca.StackSettings` (existing).
- Produces (later tasks rely on these exact names):
  - `private[orca] case class AgentSpec(backend: BackendTag, model: Option[String])` with `AgentSpec.parse(value: String): Either[String, AgentSpec]`
  - `private[orca] case class AgentSettings(planning: Option[AgentSpec] = None, coding: Option[AgentSpec] = None, review: Option[AgentSpec] = None)` with `def orElse(fallback: AgentSettings): AgentSettings` (per-role first-`Some` wins) and `AgentSettings.empty`
  - `private[orca] case class ParsedSettings(stack: StackSettings, agents: AgentSettings)`
  - `private[orca] enum SettingsScope: case Project, UserGlobal`
  - `SettingsFile.parse(content: String, scope: SettingsScope): Either[SettingsError, ParsedSettings]`
  - `SettingsFile.hasStackLines(content: String): Boolean` — true when any line, live or `#`-commented, names a stack key (`format`/`lint`/`test` followed by optional spaces and `=`, after stripping leading whitespace and `#`s). The discovery trigger (design decision 7): discovery-written files always contain at least commented stack lines, so only a genuinely stack-silent hand-written file returns false.

  (No separate role enum: `SettingKey.PlanningAgent`/`CodingAgent`/`ReviewAgent` and `AgentSettings`' three fields are the only namings of the triple — a third would be dead weight.)

- [ ] **Step 1: Write failing parser tests** in `SettingsFileTest` (follow the suite's existing style):
  - `parse` of `codingAgent = codex` (Project scope) → `agents.coding == Some(AgentSpec(BackendTag.Codex, None))`, stack empty.
  - `planningAgent = claude:opus` → `Some(AgentSpec(BackendTag.ClaudeCode, Some("opus")))`; `reviewAgent = opencode:anthropic/claude-sonnet-4-5` keeps everything after the first `:` verbatim.
  - Repeated `codingAgent` → `Left` mentioning "appears twice" (new `SettingsError.DuplicateKey`).
  - `codingAgent = mistral` → `Left` naming the five valid harnesses (new `SettingsError.InvalidAgentSpec`).
  - `codingAgent =` (empty value) → key treated as absent (existing empty-value rule).
  - UserGlobal scope: `format = cargo fmt` → `Left` saying stack keys are project-only (new `SettingsError.NotAllowedInGlobal`); agent keys parse fine; Project scope still accepts both families and keeps stack append semantics.
  - Existing stack-key tests keep passing (result now under `.stack`).

  In `AgentSpecTest`: `parse("claude")`, `parse("claude:opus")`, `parse("gemini:gemini-2.5-pro")`, `parse("")` → `Left`, `parse("unknown:x")` → `Left` listing valid names, `parse("claude:")` → model `None` (trailing colon, empty model ≡ none).

  `hasStackLines` tests: `"format = cargo fmt"` → true; `"# format =   (no formatter found)"` → true (commented counts — the frozen discovery file must not re-trigger); `"# lint = just check   (just: not found)"` → true; `"codingAgent = codex"` alone (plus header comments) → false; `"# orca settings — edit freely"` → false (a stack word mid-comment without `=` doesn't count); empty string → false.

- [ ] **Step 2: Run to verify failure** — `sbt --client "flow/testOnly orca.settings.*"` — expect compile errors (missing types), then red.

- [ ] **Step 3: Implement.** `AgentSpec.scala`:

```scala
package orca.settings

import orca.agents.BackendTag

/** One configured role agent: a harness plus an optional model pin, the parsed
  * form of a `<harness>[:<model>]` settings value. The model string is passed
  * verbatim to the harness's `withModel` — orca does not normalise or validate
  * model ids.
  */
private[orca] case class AgentSpec(backend: BackendTag, model: Option[String])

private[orca] object AgentSpec:
  /** Settings-file harness names — deliberately lowercase and distinct from
    * [[BackendTag.wireName]] (which is frozen for the progress log).
    */
  val harnessNames: Map[String, BackendTag] = Map(
    "claude" -> BackendTag.ClaudeCode,
    "codex" -> BackendTag.Codex,
    "opencode" -> BackendTag.Opencode,
    "pi" -> BackendTag.Pi,
    "gemini" -> BackendTag.Gemini
  )

  /** Left = human-readable problem for the settings-error message. Split at
    * the FIRST `:` so a model id containing `:` survives; an empty model part
    * means no pin.
    */
  def parse(value: String): Either[String, AgentSpec] =
    val (harness, model) = value.indexOf(':') match
      case -1 => (value.trim, None)
      case i  => (value.take(i).trim, Some(value.drop(i + 1).trim).filter(_.nonEmpty))
    harnessNames.get(harness) match
      case Some(tag) => Right(AgentSpec(tag, model))
      case None =>
        Left(
          s"unknown harness `$harness` — valid: " +
            harnessNames.keys.toList.sorted.mkString(", ")
        )

/** The agent keys of one settings file; [[orElse]] layers a file over a
  * fallback per role (project over user-global).
  */
private[orca] case class AgentSettings(
    planning: Option[AgentSpec] = None,
    coding: Option[AgentSpec] = None,
    review: Option[AgentSpec] = None
):
  def orElse(fallback: AgentSettings): AgentSettings =
    AgentSettings(
      planning = planning.orElse(fallback.planning),
      coding = coding.orElse(fallback.coding),
      review = review.orElse(fallback.review)
    )

private[orca] object AgentSettings:
  val empty: AgentSettings = AgentSettings()
```

  In `SettingsFile.scala`:
  - Add `SettingKey` cases `PlanningAgent("planningAgent")`, `CodingAgent("codingAgent")`, `ReviewAgent("reviewAgent")`, and `SettingsScope`:

```scala
/** Which settings file is being parsed: the per-project file accepts every
  * key; the user-global file accepts only agent keys (stack commands are
  * per-project by nature).
  */
private[orca] enum SettingsScope:
  case Project, UserGlobal
```

  - Add `SettingsError` cases with `message` arms:

```scala
  case DuplicateKey(line: Int, key: String)
  case InvalidAgentSpec(line: Int, key: String, problem: String)
  case NotAllowedInGlobal(line: Int, key: String)
```

    messages: `s"line $line: `$key` appears twice — agent keys are single-valued"`; `s"line $line: $key: $problem"`; `s"line $line: `$key` is not valid in the user-global settings file — stack commands (format, lint, test) are per-project; valid keys here: planningAgent, codingAgent, reviewAgent"`.
  - Change `parse` to `parse(content: String, scope: SettingsScope): Either[SettingsError, ParsedSettings]`, folding over `ParsedSettings` (define it in this file next to `SettingsError`); the per-line handler routes stack keys through the existing `append` (rejecting them first when `scope == UserGlobal`) and agent keys through `AgentSpec.parse` (mapping `Left(problem)` to `InvalidAgentSpec`), rejecting a role already set with `DuplicateKey`. Unknown keys keep the existing `UnknownKey` error, whose valid-keys list now includes all six.
  - `Header` text: replace "orca stack settings" with "orca settings" (the file now carries agent keys too); the delete-to-rediscover line becomes "Delete the stack lines (format/lint/test, commented ones too) to re-run auto-discovery." — deleting the whole file still works but would also drop agent keys.
  - Add `hasStackLines`:

```scala
  /** True when any line of `content` names a stack key — live or commented.
    * The discovery trigger (ADR 0020): a discovery-written file always
    * carries at least commented stack lines, so only a hand-written file
    * with no stack content at all re-triggers discovery.
    */
  def hasStackLines(content: String): Boolean =
    val stackLine = "^[#\\s]*(format|lint|test)\\s*=".r
    content.linesIterator.exists(l => stackLine.findPrefixOf(l.trim).isDefined)
```

- [ ] **Step 4: Run to green** — `sbt --client "flow/testOnly orca.settings.*"`, then `sbt --client flow/test` (property test `SettingsFilePropertyTest` may need the `.stack` accessor update). Expect PASS, zero warnings.

- [ ] **Step 5: Format + commit** — `sbt --client scalafmtAll`, then `git add flow/ && git commit -m "Settings: agent role keys (planningAgent/codingAgent/reviewAgent) with scope-aware parse"`.

---

### Task 2: User-global settings location

**Files:**
- Create: `flow/src/main/scala/orca/settings/GlobalSettings.scala`
- Test: `flow/src/test/scala/orca/settings/GlobalSettingsTest.scala`

**Interfaces:**
- Produces: `private[orca] object GlobalSettings` with `def path(env: String => Option[String], home: os.Path): os.Path` and `def default: os.Path` (= `path(sys.env.get, os.home)`).

- [ ] **Step 1: Failing tests** — `path(env = Map("XDG_CONFIG_HOME" -> "/tmp/xdg").get, home = os.root / "home" / "u")` → `/tmp/xdg/orca/settings.properties`; unset → `/home/u/.config/orca/settings.properties`; relative value (`"rel/path"`) → ignored per spec, falls back to `~/.config`; empty string → fallback.
- [ ] **Step 2: Verify red** — `sbt --client "flow/testOnly orca.settings.GlobalSettingsTest"`.
- [ ] **Step 3: Implement:**

```scala
package orca.settings

/** The user-global settings file (ADR 0020):
  * `$XDG_CONFIG_HOME/orca/settings.properties`, defaulting to
  * `~/.config/orca/settings.properties` (XDG Base Directory spec). A relative
  * `XDG_CONFIG_HOME` is ignored, as the spec mandates.
  */
private[orca] object GlobalSettings:
  def path(env: String => Option[String], home: os.Path): os.Path =
    val configHome = env("XDG_CONFIG_HOME")
      .filter(v => v.nonEmpty && v.startsWith("/"))
      .map(os.Path(_))
      .getOrElse(home / ".config")
    configHome / "orca" / "settings.properties"

  def default: os.Path = path(sys.env.get, os.home)
```

- [ ] **Step 4: Green** — `sbt --client "flow/testOnly orca.settings.GlobalSettingsTest"`.
- [ ] **Step 5: Format + commit** — `git commit -m "Settings: user-global file location (XDG)"`.

---

### Task 3: Drop the `CanAskUser` typeclass

All five backends carry a given, so the constraint excludes nothing (and the raw `resultAs[O].interactive` door never had it). Removing it now unblocks `Plan.interactive` on the role accessors later.

**Files:**
- Delete: `tools/src/main/scala/orca/agents/CanAskUser.scala`
- Delete: `gemini/src/test/scala/orca/tools/gemini/GeminiCanAskUserTest.scala` (its regression target — the given — no longer exists; the actual gemini `ask_user` wiring stays covered by the MCP-settings tests in `GeminiSettingsTest`/`GeminiBackendTest`)
- Modify: `flow/src/main/scala/orca/plan/Plan.scala` (bounds at lines 132, 145, 159, 214; scaladoc mentions at lines 76 and 121), `runner/src/main/scala/orca/exports.scala` (drop the `CanAskUser` export), `runner/src/test/scala/flowtests/FlowCompilesTest.scala:289` (comment references the typeclass)

**Interfaces:**
- Consumes: nothing new.
- Produces: `Plan.interactive.from` / `.assessThenPlan` / `.triage` and the private `interactiveResult` signed `[B <: BackendTag]` with no context bound — later tasks rely on `Plan.interactive.from(userPrompt, planningAgent)` compiling for an abstract `B`.

- [ ] **Step 1: Write the failing compile-level test** — in `FlowCompilesTest`, add a fixture cell proving interactive planning compiles against a type-abstract agent (today it only compiles for concrete backends):

```scala
def interactivePlanAgnostic[B <: BackendTag](
    a: Agent[B]
)(using FlowContext, InStage): Unit =
  val _ = Plan.interactive.from("prompt", a)
```

- [ ] **Step 2: Verify it fails** — `sbt --client "runner/testOnly flowtests.FlowCompilesTest"` → compile error: no `CanAskUser[B]` instance for abstract `B`.
- [ ] **Step 3: Implement** — delete the two files; in `Plan.scala` change every `[B <: BackendTag: CanAskUser]` to `[B <: BackendTag]`, drop the import, and rewrite the two scaladoc mentions (line 121's paragraph becomes a note that every backend exposes `ask_user` — claude/codex/gemini via the shared MCP server, opencode natively, pi via the extension); remove the export line and the FlowCompilesTest comment sentence.
- [ ] **Step 4: Run to green** — `sbt --client compile && sbt --client test` (background the full run). Expect PASS, zero warnings (the unused-import warning from a leftover `CanAskUser` import would fail the build — the `-Wunused:all` floor catches stragglers).
- [ ] **Step 5: Format + commit** — `sbt --client scalafmtAll`, `git commit -m "Drop CanAskUser: every backend implements ask_user, the bound excluded nothing"`.

---

### Task 4: Role accessors on `FlowContext` (additive, lead-backed)

Keeps the build green by ADDING the three role members while `agent`/`LeadB` still exist; `DefaultFlowContext` temporarily backs all three with the lead. Library call sites move to the role accessors here.

**Files:**
- Modify: `flow/src/main/scala/orca/FlowContext.scala`, `flow/src/main/scala/orca/accessors.scala`, `flow/src/main/scala/orca/Flow.scala:180` (commit-message default), `flow/src/main/scala/orca/review/ReviewLoop.scala:285`, `flow/src/main/scala/orca/review/ReviewerSelector.scala:117`
- Modify: `runner/src/main/scala/orca/runner/DefaultFlowContext.scala`, `runner/src/main/scala/orca/runner/FlowLifecycle.scala:111` (rehydration lead → `ctx.codingAgent`)
- Test: `flow/src/test/scala/orca/TestFlowContext.scala` (implement the members), plus whatever fixture contexts fail to compile (`grep -rln "extends FlowControl\|extends FlowContext" */src/test`)

**Interfaces:**
- Produces on `FlowContext` (final shape, relied on by every later task):

```scala
  type PlanB <: BackendTag
  type CodeB <: BackendTag
  type ReviewB <: BackendTag

  /** The planning-role agent (ADR 0020): resolved from settings
    * (`planningAgent = <harness>[:<model>]`), default claude. Scripts hand it
    * to `Plan.*`.
    */
  def planningAgent: Agent[PlanB]

  /** The coding-role agent — the run's primary: implementer sessions, branch
    * naming, stack discovery, and default commit messages run here.
    */
  def codingAgent: Agent[CodeB]

  /** The review-role agent: `allReviewers(reviewAgent)`, the reviewer-picker
    * and the lint summariser default to its tiers.
    */
  def reviewAgent: Agent[ReviewB]
```

- and in `accessors.scala` (carry over the helper-authoring guidance from the current `agent` scaladoc, retargeted at the three members):

```scala
def planningAgent(using ctx: FlowContext): Agent[ctx.PlanB] = ctx.planningAgent
def codingAgent(using ctx: FlowContext): Agent[ctx.CodeB] = ctx.codingAgent
def reviewAgent(using ctx: FlowContext): Agent[ctx.ReviewB] = ctx.reviewAgent
```

- [ ] **Step 1: Failing compile-level test** — in `flow/src/test/scala/orca/AccessorsTest.scala` add a test asserting the three accessors resolve and that a session minted from `codingAgent` threads: `val s = codingAgent.session(...)` typed `FlowSession[ctx.CodeB]` (compile-only fixture in the existing style of that suite/`FlowCompilesTest`).
- [ ] **Step 2: Red** — `sbt --client flow/test` fails to compile (members missing).
- [ ] **Step 3: Implement** the trait members + accessors; in `DefaultFlowContext[B]` add `type PlanB = B; type CodeB = B; type ReviewB = B` and `val planningAgent/codingAgent/reviewAgent: Agent[B] = agent` (temporary — replaced in Task 6); implement in `TestFlowContext` the same way. Switch call sites: `Flow.scala` `fc.agent.cheapOneShot` → `fc.codingAgent.cheapOneShot`; `ReviewLoop` `Lint(ctx.stackSettings.lint, ctx.agent.cheap)` → `ctx.reviewAgent.cheap` (update the adjacent comment's `ctx.agent` mentions); `ReviewerSelector` `agentDriven(ctx.agent.cheap)` → `agentDriven(ctx.reviewAgent.cheap)`; `FlowLifecycle.run` rehydration `ctx.agent` → `ctx.codingAgent` (untagged records go to the coding role).
- [ ] **Step 4: Green** — `sbt --client compile && sbt --client flow/test && sbt --client runner/test` (background the runner run; it is slow).
- [ ] **Step 5: Format + commit** — `git commit -m "FlowContext: planningAgent/codingAgent/reviewAgent role accessors (lead-backed)"`.

---

### Task 5: Runner-side role resolution

**Files:**
- Create: `runner/src/main/scala/orca/runner/RoleAgents.scala`
- Test: `runner/src/test/scala/orca/runner/RoleAgentsTest.scala`

**Interfaces:**
- Consumes: `AgentSettings`, `AgentSpec` (Task 1), `WiredAgents` (existing).
- Produces:

```scala
private[runner] case class ResolvedRoles(
    planning: Agent[?],
    coding: Agent[?],
    review: Agent[?]
)

private[runner] object RoleAgents:
  /** Resolve the three role agents against the run's wired set — every role
    * shares a wired backend, so events and close() behave exactly as for the
    * wired five. An unset role defaults to claude with no model pin.
    */
  def resolve(settings: AgentSettings, agents: WiredAgents): ResolvedRoles

  private def one(spec: Option[AgentSpec], agents: WiredAgents): Agent[?] =
    spec match
      case None => agents.claude
      case Some(AgentSpec(tag, model)) =>
        tag match
          case BackendTag.ClaudeCode =>
            model.fold(agents.claude)(m => agents.claude.withModel(Model(m)))
          case BackendTag.Codex =>
            model.fold(agents.codex)(m => agents.codex.withModel(Model(m)))
          case BackendTag.Opencode =>
            model.fold(agents.opencode)(m => agents.opencode.withModel(m))
          case BackendTag.Pi =>
            model.fold(agents.pi)(m => agents.pi.withModel(Model(m)))
          case BackendTag.Gemini =>
            model.fold(agents.gemini)(m => agents.gemini.withModel(Model(m)))
```

  (`opencode.withModel` takes the raw `provider/model` string and applies its existing validation — an invalid form surfaces as a setup failure.)

- [ ] **Step 1: Failing tests** using the existing runner stubs (`StubClaudeAgent`, `CannedDiscoveryAgent` — see `runner/src/test/scala/orca/runner/`): unset settings → all three are the wired claude (`backendIdentity` shared / `eq`); `coding = Some(AgentSpec(Codex, None))` → the wired codex; model pin → resolved agent is a `withModel` sibling sharing the wired backend (`isWiredBackend` true) — for claude assert via a stub that records `withModel` calls, or assert `backendIdentity` equality plus non-`eq` instance.
- [ ] **Step 2: Red** — `sbt --client "runner/testOnly orca.runner.RoleAgentsTest"`.
- [ ] **Step 3: Implement** as above.
- [ ] **Step 4: Green**, zero warnings.
- [ ] **Step 5: Format + commit** — `git commit -m "Runner: resolve role agents from settings against the wired set"`.

---

### Task 6: Rewire `flow(...)` — settings-driven roles, existential context, drop the selector

The core (and largest) task; one coherent reviewable unit because the signature change, the context re-typing, and the test updates cannot be split without breaking the build.

**Files:**
- Modify: `runner/src/main/scala/orca/flow.scala`, `runner/src/main/scala/orca/runner/DefaultFlowContext.scala`, `runner/src/main/scala/orca/runner/FlowLifecycle.scala`, `flow/src/main/scala/orca/FlowContext.scala`, `flow/src/main/scala/orca/accessors.scala`, `flow/src/main/scala/orca/AgentSet.scala` (scaladoc only — selector wording)
- Test: all `flow(...)` call sites — `runner/src/test/scala/{flowtests/FlowCompilesTest, orca/runner/*}.scala`, `flow/src/test/scala/orca/{FlowTest, FlowSessionTest, ...}` fixtures; NEW `runner/src/test/scala/orca/runner/RoleSettingsFlowTest.scala`

**Interfaces:**
- Produces the final public entry point (README documents this exact shape):

```scala
def flow(
    args: OrcaArgs,
    workDir: os.Path = os.pwd,
    interaction: Option[Interaction] = None,
    extraListeners: List[OrcaListener] = Nil,
    branchNaming: Option[BranchNamingStrategy] = None,
    stackSettings: Option[StackSettings] = None,
    // Per-role programmatic overrides — win over both settings files. Selector-
    // shaped so a derived sibling of a wired agent stays expressible
    // (`Some(_.claude.opus)`); also the seam tests use instead of the global file.
    planningAgent: Option[AgentSet => Agent[?]] = None,
    codingAgent: Option[AgentSet => Agent[?]] = None,
    reviewAgent: Option[AgentSet => Agent[?]] = None,
    returnToStartBranch: Boolean = false,
    progressStore: Option[ProgressStore] = None,
    claude: Option[AgentWiring => Ox ?=> ClaudeAgent] = None,
    codex: Option[AgentWiring => Ox ?=> CodexAgent] = None,
    opencode: Option[AgentWiring => Ox ?=> OpencodeAgent] = None,
    pi: Option[AgentWiring => Ox ?=> PiAgent] = None,
    gemini: Option[AgentWiring => Ox ?=> GeminiAgent] = None,
    git: Option[GitTool] = None,
    gh: Option[GitHubTool] = None,
    fs: Option[FsTool] = None,
    prompts: Prompts = DefaultPrompts,
    pricing: PriceList = Pricing.default
)(body: FlowControl ?=> Unit): Unit
```

- `runFlow` mirrors it (internal `globalSettingsPath: os.Path = GlobalSettings.default` parameter added for tests). `FlowLifecycle.setup` takes the pre-parsed `SettingsResolution` instead of `settingsOverride` and re-reading the file.

- [ ] **Step 1: New failing end-to-end tests** in `RoleSettingsFlowTest` (model on `OrcaTest`/`OrcaOverridesTest` fixtures — temp git repo, stub agent factories via `claude = Some(_ => stub)` etc.):
  - No settings anywhere → all three roles resolve to the (stubbed) claude; a body reading `codingAgent`/`planningAgent`/`reviewAgent` sees it.
  - Project file `codingAgent = codex` → body's `codingAgent` is the stubbed codex; `planningAgent` still claude.
  - Global file (passed via `runFlow(globalSettingsPath = ...)`) `reviewAgent = codex`, project file `reviewAgent = gemini` → project wins.
  - Programmatic `codingAgent = Some(_.gemini)` beats a project file naming codex.
  - Malformed agent value in the project file → `SurfacedFlowFailure` before any branch mutation (assert `git.currentBranch` unchanged, mirroring the existing malformed-stack-settings test in `FlowLifecycleTest`); same for a malformed GLOBAL file, and for a malformed project file when `stackSettings = Some(...)` is passed (the stricter behaviour design decision 6 documents).
  - `stackSettings = Some(...)` override + project file carrying `codingAgent = codex` → codex still selected (agent keys honoured under a stack override).
  - Agents-only project file (`codingAgent = codex`, no stack lines) → discovery RUNS (stub the discovery reply via `CannedDiscoveryAgent`) and the file afterwards contains BOTH the original `codingAgent` line and the appended stack entries; a discovery-written file with only commented stack lines (`# format =   (reason)`) does NOT re-trigger discovery on the next run.
  - The setup event stream contains one `Step` matching `agents: planning=... coding=... review=...` with per-role sources (default/project/global/override).
- [ ] **Step 2: Red** — these don't compile yet (`flow` still wants a selector).
- [ ] **Step 3: Implement.**
  - `FlowContext`: delete `LeadB` + `agent` (fold their scaladoc guidance into `CodeB`/`codingAgent`); `accessors.scala`: delete `def agent`.
  - `DefaultFlowContext` becomes:

```scala
private[orca] class DefaultFlowContext[
    PB <: BackendTag,
    CB <: BackendTag,
    RB <: BackendTag
](
    val userPrompt: String,
    val workDir: os.Path,
    dispatcher: EventDispatcher,
    val planningAgent: Agent[PB],
    val codingAgent: Agent[CB],
    val reviewAgent: Agent[RB],
    wired: WiredAgents,
    ...
) extends FlowControl, orca.StageFrames:
  type PlanB = PB
  type CodeB = CB
  type ReviewB = RB
```

    `close()` fans out over `wired.all ++ List(planningAgent, codingAgent, reviewAgent)` (unconditional append, same double-close-safe rationale as today's lead).
  - `runFlow`: read + parse both settings files right after `WiredAgents.build`, INSIDE the pre-context `surfaced` bracket (where lead resolution lives today — the dispatcher exists by then, so parse/resolution failures reach the event surface before aborting). Project file via `OrcaDir.settingsPath(workDir)` — reuse `FlowLifecycle`'s read-error wrapping, moved here; global via the `globalSettingsPath` param, `SettingsScope.UserGlobal`, absent → `AgentSettings.empty`. Merge `projectAgents.orElse(globalAgents)`; resolve `RoleAgents.resolve(merged, agents)`; apply the three selector overrides on top (`override.map(_(agents)).getOrElse(resolved.role)`); emit the per-role source announcement `Step` (design decision 10 — track each role's source while merging: override/project/global/default) and the existing foreign-agent warning per role (`agents.isWiredBackend`); update the ownership-transfer guard from `var lead: Option[Agent[B]]` to `var roles: List[Agent[?]]`. The stack resolution enum gains the discovery-trigger refinement (design decision 7): `NeedsDiscovery(existingContent: Option[String])` — produced when the file is absent (`None`) or present-but-stack-silent per `SettingsFile.hasStackLines` (`Some(content)`, its agent keys already extracted). Rewrite `flow`'s ~60-line entry-point scaladoc: the selector paragraphs are replaced by the settings-resolution story (files, precedence, role overrides, the announcement `Step`). Then open the existentials:

```scala
          (planning, coding, review) match
            case (p: Agent[pb], c: Agent[cb], r: Agent[rb]) =>
              val ctx = new DefaultFlowContext[pb, cb, rb](
                userPrompt = args.userPrompt,
                workDir = workDir,
                dispatcher = dispatcher,
                planningAgent = p,
                codingAgent = c,
                reviewAgent = r,
                ...
              )
              ...
```

    (If the compiler flags the type-variable patterns as unchecked, extract a small `private def open3[T](p: Agent[?], c: Agent[?], r: Agent[?])(f: [X <: BackendTag, Y <: BackendTag, Z <: BackendTag] => (Agent[X], Agent[Y], Agent[Z]) => T): T` using one nested match per agent — same mechanism, warning-free.)
  - `FlowLifecycle.setup`: signature takes `resolution: SettingsResolution` (made `private[runner]`) and `agent: Agent[?]` = the resolved coding agent (branch naming + discovery unchanged, now explicitly the coding role); delete `resolveStackSettings`'s call from `setup` (the function moves to `runFlow`, keeping its pre-`ensureClean` position and its hard-abort semantics; `warnIfSettingsIgnored` keeps its `settingsOverride.isEmpty` gate — thread a `stackOverridden: Boolean` through). The discovery write path never writes agent keys and branches on `NeedsDiscovery.existingContent`: `None` → write the full rendered file with the updated header (Task 1); `Some(content)` → append `"\n" + entries.map(renderEntry)` to the existing content, leaving the user's agent lines and comments untouched (a `SettingsFile.renderAppend(entries): String` sibling of `render` keeps the entry formatting in one place). The dedicated `orca: stack settings (discovered)` commit covers both shapes unchanged.
  - Update every existing `flow(args, _.claude, ...)`/`runFlow(...)` call site in tests: drop the selector; where a test pinned a non-claude lead, pass `codingAgent = Some(_.codex)` (etc.); `LeadAgentIdentityTest` re-targets the coding role; `FlowCompilesTest` gets the new signature and role-accessor usage (its fixture is the compile-level spec of the public API).
- [ ] **Step 4: Green** — `sbt --client compile`, then full `sbt --client test` in the background (runner suites are slow). Zero warnings.
- [ ] **Step 5: Format + commit** — `git commit -m "flow(): settings-configured role agents replace the lead selector (ADR 0020)"`.

---

### Task 7: Documentation, examples, ADR

**Files:**
- Create: `adr/0020-configurable-role-agents.md`
- Modify: `README.md` (flow row + `agent` row in the API table, "Coding agent tools" section, example at the top, Stack settings section — rename to "Settings", precedence line, new agent-keys subsection with the value grammar and the global-file location, sessions note about backend swaps minting fresh), `AGENTS.md` (the `LeadB` paragraph → `CodeB`/roles), `examples/*.sc` (all six), `examples/runnable/*/README.md` if they show `_.claude`
- Test: `sbt --client "runner/testOnly flowtests.FlowCompilesTest"` + `examples` compile check (`scala-cli compile examples/implement.sc` requires a published snapshot — instead keep `FlowCompilesTest` as the compile gate, and update examples to match it exactly)

**Interfaces:** none (docs only), but examples must use only the Task 6 surface.

- [ ] **Step 1: Write ADR 0020** — context (per-run selector → per-user/project config), decisions 1–11 from the design section verbatim (file grammar, locations/precedence, XDG rationale, three type members + existential opening, role mapping, sequencing, the stack-aware discovery trigger + append-write, sessions across config changes, the `CanAskUser` drop, the role announcement, the ADR 0019 "user-level layer is a non-goal" note superseded, non-goals), consequences (breaking `flow` signature at 0.0.x; stricter malformed-file abort under a stack override and for the global file; ADR 0019's "file exists ⇒ discovery ran" invariant replaced by "stack lines exist ⇒ discovery ran"; `epic.sc`-style cross-backend flows now expressible in settings; a future backend without `ask_user` must reintroduce an interactive guard).
- [ ] **Step 2: Update examples.** `implement.sc` becomes the canonical shape (README mirrors it):

```scala
// Roles (planning / coding / review) come from settings.properties —
// per-project `.orca/settings.properties`, else ~/.config/orca/settings.properties,
// else claude for everything. Bodies can still name a concrete harness
// (`claude`, `codex.mini`, …) where a flow wants one.
flow(OrcaArgs(args)):
  val plan = stage("Plan"):
    Plan.autonomous.from(userPrompt, planningAgent).value

  val session = codingAgent.session("implementer", seed = plan.brief)

  for task <- plan.tasks do
    stage(s"Task: ${task.title}"):
      session.run(task.description)
      reviewAndFixLoop(
        coderSession = session,
        reviewers = allReviewers(reviewAgent),
        task = task.title.value
      )
```

  Same treatment for the other five (`epic.sc` keeps its deliberate concrete `codex` reviewers as the "flows can still pin a harness" showcase; `implement-interactive.sc` switches to `Plan.interactive.from(userPrompt, planningAgent)` — with `CanAskUser` gone, interactive planning follows the configured role like everything else).
- [ ] **Step 3: README.** Update: the top example; the API-reference `flow` row (new signature summary + "roles from settings, default claude; per-role overrides `planningAgent`/`codingAgent`/`reviewAgent` win over files"); replace the `agent` accessor row with three rows (`planningAgent`/`codingAgent`/`reviewAgent`, each `Agent[ctx.<Role>B]`); rewrite "Coding agent tools" bullets ("The role agents — ..." replacing "The leading agent — `agent`", and dropping the "interactive planning needs a concrete backend" clause — it no longer does); Settings section: document both files, the key grammar table (`planningAgent | codingAgent | reviewAgent = <harness>[:<model>]`, harness list, verbatim model pass-through with per-harness examples `claude:opus`, `codex:gpt-5-mini`, `opencode:anthropic/claude-haiku-4-5`), the full precedence chain, the note that agent keys are read even under `flow(stackSettings = ...)`, and the discovery paragraph updated for the stack-aware trigger ("delete the stack lines to re-run discovery"; a hand-written agents-only file still gets discovery, with stack entries appended). Keep every reference table (repo memory: never cut the reference tables).
- [ ] **Step 4: AGENTS.md** — update the path-dependent-accessor paragraph (`agent: Agent[ctx.LeadB]` → the three role accessors, same helper-authoring advice) and the "user-facing surface" sentence naming `agent`.
- [ ] **Step 5: Verify** — `sbt --client test` green (FlowCompilesTest covers the documented shapes); `grep -rn "_.claude\|\bagent\b" README.md examples/*.sc` shows no stale selector/accessor references.
- [ ] **Step 6: Format + commit** — `git commit -m "Docs: configurable role agents (ADR 0020)"`.

---

## Self-review notes

- Spec coverage: default-claude ✔ (Task 5 `one(None)`), three roles ✔ (Tasks 4/6), concrete accessors still usable ✔ (unchanged + documented Task 7), file locations researched ✔ (Task 2, XDG), `harness:model` keys ✔ (Task 1), type-safe `Agent[B1]` ✔ (Tasks 4/6), `.cheap` for simple work ✔ (role mapping, decision 5), interactive planning on configured agents ✔ (Task 3 drops `CanAskUser`; Task 7 switches `implement-interactive.sc`).
- Confirmed judgement calls (user-approved): `codingAgent` as the primary for branch naming/discovery/rehydration; review-machinery defaults on `reviewAgent.cheap`; selector-shaped programmatic overrides on `flow(...)`; verbatim model pass-through with no alias table; `CanAskUser` dropped rather than kept vacuous.
- Review round (simplicity/correctness/completeness) outcomes folded in: the existential-opening mechanism was verified empirically — the tuple match with type-variable patterns AND the `open3` poly-function fallback both compile and run under `-Werror` with this repo's warning flags on Scala 3.8.4 (implementers should not need the fallback); the agents-only-file discovery trap is closed by decision 7 (`hasStackLines` trigger + append-write); the unused `AgentRole` enum was cut; malformed-global-file and role-announcement behaviour are specified and tested; `Lint.agent: Agent[?]` and `Plan.interactive.from(userPrompt, agent, ...)` signatures were checked against source.
