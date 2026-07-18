# 0020. Configurable role agents

Status: Accepted · Date: 2026-07-17
Supersedes: [ADR 0019](0019-project-stack-settings.md)'s "a user-level
(`~/.config/orca`) settings layer is a non-goal" note and its "file exists ⇒
discovery already ran" invariant
Related: [ADR 0018](0018-stage-bound-flow-runtime.md) (`FlowContext`, the R31
lead-agent selector this replaces), [ADR 0019](0019-project-stack-settings.md)
(settings-file format and auto-discovery, extended here), [ADR 0012](0012-mcp-host-bridge.md)
(`ask_user` MCP bridge — the mechanism that makes decision 9 possible),
[ADR 0003](0003-pluggable-llm-backends.md) (backend SPI)

## Context

`flow(...)` took a required `agent: FlowContext => Agent[B]` selector (ADR
0018 R31): every script named its backend at the call site (`_.claude`,
`_.codex`, …), and the body read the resolved lead through one
backend-agnostic accessor (`agent`). One selector for the whole run was
workable while orca had one coding agent to configure, but it conflates three
distinct jobs a run performs — planning, coding, and reviewing — which real
flows already run on different tiers of the same backend (`claude.opus` to
plan, bare `claude` to implement, `claude.cheap` to review) or, per
`epic.sc`, on different backends entirely (claude implements, codex
reviews). Pinning the backend in the script also meant that trying a
different backend, or matching a team's licensing/cost constraints, meant
editing every flow script that used it — there was no way to default a
backend project- or user-wide.

ADR 0019 had already established `{workDir}/.orca/settings.properties`: a
committed, hand-editable, strictly-parsed `key = value` file carrying
per-project stack commands. That file's format, precedence machinery, and
committed-by-default `.orca/` convention are the natural place to also carry
agent configuration — and per ADR 0019's own non-goals list, a user-level
(`~/.config/orca`) layer was left explicitly out of scope for later. This ADR
adds that layer, extends the file format with three single-valued agent
keys, and replaces the `flow(...)` selector with three settings-resolved role
agents.

## Decision

### 1. File format

Both settings files grow three new keys, in the existing strict line format
(ADR 0019): `#` comments, `key = value`, the value taken verbatim (trimmed)
after the first `=`. Unlike the appending stack keys (`format`/`lint`/`test`),
an agent key is single-valued — a repeated occurrence is a parse error:

```properties
planningAgent = claude:opus
codingAgent = codex
reviewAgent = opencode:anthropic/claude-sonnet-4-5
```

Value grammar: `harness:model`, split at the FIRST `:` (so a model id
containing `:` survives), model optional. `harness` ∈ `claude | codex |
opencode | pi | gemini` — an unrecognised name is a parse error naming the
valid set. The model part is passed **verbatim** to the harness's
`withModel` — orca does not normalise or validate model ids, consistent with
`Model`'s existing contract: for claude the CLI itself accepts aliases
(`opus`, `sonnet`), for opencode it is the provider-qualified `provider/model`
form its existing validation checks. An empty value is equivalent to the key
being absent (the existing parser rule for stack keys too).

### 2. Locations & precedence

Per role key: `flow(...)` programmatic override > `{workDir}/.orca/settings.properties`
> `$XDG_CONFIG_HOME/orca/settings.properties` (default `~/.config/orca/settings.properties`,
per the XDG Base Directory spec — the convention `gh`/`git` also follow, on
macOS too) > built-in default (claude, no model pin). A relative or unset
`XDG_CONFIG_HOME` falls back to `~/.config`, per spec. An absent global file
is simply skipped — no error.

The global file may contain ONLY agent keys: stack commands (`format`/`lint`/
`test`) are per-project by nature, so a stack key there is a parse error
naming the valid global keys. This keeps the two files' roles disjoint: the
project file is project-and-agent-scoped, the global file is agent-only.

### 3. Type safety

`FlowContext` gains three abstract type members — `PlanB`, `CodeB`, `ReviewB
<: BackendTag` — and three accessors, `planningAgent: Agent[PlanB]`,
`codingAgent: Agent[CodeB]`, `reviewAgent: Agent[ReviewB]`, replacing the
single `LeadB`/`agent` pair from ADR 0018 R31. Each is a type *member*, not a
parameter, so `FlowContext` itself stays unparametrised — exactly the
mechanism `LeadB` used. The runtime resolves the three roles as `Agent[?]`
values at runtime (their backends are only known after settings are read) and
opens all three existentials in one match on a 3-tuple of `Agent[pb]`,
`Agent[cb]`, `Agent[rb]` type-variable patterns, constructing
`DefaultFlowContext[pb, cb, rb]` — verified to compile and run under this
repo's `-Werror` flags on Scala 3.8.4 (an `open3`-style poly-function
fallback was prepared but is not needed).

Helpers that combine a role's agent with its session keep the existing
`[B <: BackendTag]` shape (`reviewAndFixLoop(coderSession: FlowSession[B],
...)`) — a path-dependent `Agent[ctx.CodeB]` only unifies within one `using
FlowContext` in scope, so a helper factored into its own function must take
an explicit type parameter or bundle agent+session as one value
(`Sessioned[B, A]`), the same caveat `LeadB` already carried.

Rejected: `Agent[?]` accessors (existentially-typed, so a session minted from
them wouldn't thread into `session.run`/reviewers — the entire point of
`LeadB`'s design is preserved threefold); keeping a static `flow[B](...)`
type parameter (impossible — a role's backend is resolved from settings at
runtime, not known at the `flow(...)` call site).

### 4. `flow(...)` signature

The `agent` selector parameter and the `flow[B](...)` type parameter are
removed. Three optional per-role overrides replace it, selector-shaped so a
derived sibling of a wired agent stays expressible:

```scala
def flow(
    args: OrcaArgs,
    // …
    planningAgent: Option[AgentSet => Agent[?]] = None,
    codingAgent: Option[AgentSet => Agent[?]] = None,
    reviewAgent: Option[AgentSet => Agent[?]] = None
    // …
)(body: FlowControl ?=> Unit): Unit
```

`Some(_.claude.opus.withNetworkTools(...))` composes exactly like the old
selector did. These overrides are also the test seam: tests pass them in
place of a real user-global file, so no test reads the developer's actual
`~/.config`. An override that resolves to an agent built from a backend
outside the run's wired five (e.g. `_ => myPrebuiltAgent` from a separate
`AgentWiring`) still compiles — but is event-blind (never reaches this run's
dispatcher) and gets a loud resolution-time warning; the runtime still closes
it at flow end to avoid a resource leak.

### 5. Role mapping inside the library

`codingAgent` is the run's primary and inherits every job the old lead
carried: branch naming, stack auto-discovery, the default stage commit
message (`ctx.codingAgent.cheapOneShot`), untagged session-record
rehydration, and the implementer session in every example script.
`reviewAgent` drives the review machinery's defaults —
`ReviewerSelector.agentDriven`'s picker and `Configured.FromSettings`'s lint
summariser both resolve to `ctx.reviewAgent.cheap` — and scripts build
`allReviewers(reviewAgent)`. `planningAgent` is script-side only:
`Plan.autonomous.from(userPrompt, planningAgent)` /
`Plan.interactive.from(userPrompt, planningAgent)`; the library has no
built-in planning call of its own. Every role exposes a `.cheap` tier for its
own cheap/simple work, mirroring the old lead's `agent.cheap`.

### 6. Settings read sequencing

Both files are parsed once, in `runFlow`, before `FlowLifecycle.setup` — and
so before `ensureClean` — so a malformed file (project or global) aborts with
no tree mutation and no stash can hide a hand-written file behind a dirty
tree. `setup` receives the already-parsed stack resolution rather than
re-reading the project file itself.

When `flow(stackSettings = Some(...))` is passed, the project file's stack
portion is ignored and discovery is skipped, exactly as before (ADR 0019) —
but the file's agent keys are STILL read and honoured, since the override
governs the stack commands only. Consequence: a malformed project file now
aborts even under a stack override, which is stricter than ADR 0019's
original behaviour (documented in Consequences). A malformed GLOBAL file
aborts the same way — a silently-ignored config file would be a worse failure
mode than a loud abort.

### 7. Discovery trigger becomes stack-aware

ADR 0019's "file exists ⇒ discovery already ran" invariant assumed the file
had only one reason to exist. Once the file can also carry agent-only
content, a hand-written `codingAgent = codex` file with no stack lines would,
under that invariant, silently disable every format/lint/test gate forever.
The trigger is replaced: discovery runs when the project file is absent, OR
when `SettingsFile.hasStackLines(content)` is false — no line, live or
`#`-commented, names a stack key (`format`/`lint`/`test`). A
discovery-written file always contains at least commented stack lines (e.g.
`# format =   (no formatter config found)` for a task left unset), so
ADR 0019's frozen-file semantics hold exactly as before for any file
discovery has touched; only a genuinely stack-silent hand-written file
re-triggers it.

When the file already exists in that agents-only shape, the discovery write
APPENDS the rendered stack entries below the existing content (no header
duplication, agent lines untouched) instead of overwriting the file — the
dedicated `orca: stack settings (discovered)` commit is unchanged.

### 8. Sessions across config changes

A recorded session whose backend tag no longer matches its role's
currently-resolved backend mints fresh with a warning
(`warning: session '<name>' #<occ> was minted on <old>; this agent is <new>
— minting fresh`, `Session.scala`). This is not new code — the lead-swap arm
already existed for the single-lead design — but it is now reachable purely
through a settings edit (changing `codingAgent = codex` to `codingAgent =
claude` between runs), so it is documented in the README as user-facing
behaviour rather than left as an internal resume detail.

### 9. `CanAskUser` is dropped

All five backends implement `ask_user` — claude/codex/gemini via the shared
MCP bridge (ADR 0012), opencode via its native `question` tool, pi via its
extension — so the `CanAskUser[B]` typeclass excluded nothing; the raw
`agent.resultAs[O].interactive` door never carried the bound either. Deleting
it lets `Plan.interactive.from(userPrompt, planningAgent)` compile against
the abstract `ctx.PlanB`, so interactive planning becomes role-configurable
like everything else — previously it needed a concrete backend accessor
specifically because `CanAskUser` was only ever given per concrete backend.
The runtime `Conversation.canAskUser` flag — the actual SPI wiring that turns
`ask_user` on for a turn — is unaffected. If a future backend genuinely lacks
`ask_user`, a constraint can be reintroduced then; removing it now ahead of
that case would be speculative.

### 10. Resolved roles are announced

The old design made the lead visible in the script itself (`_.claude`); role
selection is now ambient configuration, so `setup` emits one `Step` event
naming each resolved role, its harness/model, and its source
(default/project/global/override) — the debugging handle for "why did codex
run here?":

```text
agents: planning=claude (default), coding=codex:gpt-5-mini (project), review=claude (global)
```

The harness+model shown is the winning `AgentSpec`; an override shows its
resolved backend's harness with no model (the override's pin isn't a spec);
the built-in default shows `claude` with no model.

### 11. Non-goals

Per-role cheap-model configuration (`withCheapModel` stays a programmatic
override only — there is no settings key for it); CLI flags for agent
selection; named presets (`preset = rust-cargo`-style, rejected for the same
reasons ADR 0019 rejected them for stack commands); Windows paths beyond the
existing `bash -c` contract.

## Consequences

- **Breaking `flow(...)` signature at 0.0.x.** The `agent` selector parameter
  and the `flow[B](...)` type parameter are gone; every script written
  against the ADR 0018 selector needs converting (all six `examples/*.sc`
  scripts, plus the README's canonical example, are converted alongside this
  ADR). Consumers are version-pinned scripts, so no deprecated alias or
  compat shim is added.
- **Stricter malformed-file abort under a stack override, and for the
  global file.** ADR 0019's `stackSettings` override used to mean the file
  was neither read nor written; now the file is still read for its agent
  keys, so a malformed project file aborts even under that override. A
  malformed global file aborts the run the same way an unreadable project
  file always has.
- **ADR 0019's "file exists ⇒ discovery already ran" invariant is replaced**
  by "stack lines exist ⇒ discovery already ran" (decision 7) — a
  distinction that only matters for the new agents-only hand-written file
  shape this ADR introduces.
- **Cross-backend flows are now expressible without an escape hatch.**
  `epic.sc`'s "claude implements, codex reviews" shape no longer needs
  hand-wiring outside the settings system — a project could set
  `codingAgent = claude` and `reviewAgent = codex` and get the same split
  from settings alone; the example script keeps its concrete accessors as a
  demonstration that pinning a harness in-body still works regardless of
  settings.
- **A future backend without `ask_user` must reintroduce an interactive
  guard** — decision 9 removes `CanAskUser` on the current five-backend
  reality, not as a permanent guarantee.
- **New public surface:** `planningAgent`/`codingAgent`/`reviewAgent` on
  `FlowContext` and as top-level accessors; the matching `flow(...)`
  override parameters; the `PlanB`/`CodeB`/`ReviewB` type members; the
  user-global settings file and its location; the `planningAgent`/
  `codingAgent`/`reviewAgent` settings keys (documented in the README).
