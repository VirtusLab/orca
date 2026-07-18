# Orca

Deterministic, AI-driven development flows.

Orca allows you to programmatically define software development workflows where
AI agents perform the coding. If you want AI-generated code to always be
reviewed by another agent, don't try to coerce the agents; just express that
requirement in code. Don't waste tokens on formatting, committing, or creating
PRs - all of this can be handled by an ordinary script.

Orca flow scripts are written in Scala, and can be run with a single command
through [scala-cli](https://scala-cli.virtuslab.org). No other dependencies need
to be installed - everything is automatically bootstrapped. Scala 3 looks like
Python, but with types - so you get quick feedback if your flow script has any
problems.

Orca's development flows are resumable, so that if work is interrupted mid-flow
for any reason, it can be continued from the last commit. 

You can use Orca to orchestrate development in any language and ecosystem.

Orca assumes that it has configured, logged-in access to Claude, Codex,
OpenCode, or Pi (depending which backend you use), as well as `gh` and `git`.

## An example flow

Save this as `implement.sc` and run it with your task:

```scala
//> using scala 3.8.4
//> using dep "org.virtuslab::orca:0.0.16"
//> using jvm 21

import orca.{*, given}

// Roles (planning / coding / review) come from settings.properties —
// per-project `.orca/settings.properties`, else ~/.config/orca/settings.properties,
// else claude for everything. Bodies can still name a concrete harness
// (`claude`, `codex.mini`, …) where a flow wants one — details under "Coding
// agent tools".
flow(OrcaArgs(args)):
  // `stage` is the committing, resumable unit of work. The plan is produced in
  // one agentic turn and recorded in the stage log; a re-run with the same
  // prompt skips this stage and reads the stored Plan back.
  val plan = stage("Plan"):
    Plan.autonomous.from(userPrompt, planningAgent).value  // .value takes the Plan, discarding the planner's session

  // Get-or-create the implementer session on the coding role, seeded with the
  // plan's brief (primes it on first use, replayed if the backend session is
  // lost on resume).
  val session = codingAgent.session("implementer", seed = plan.brief)

  // One stage per task: each stage commits its work + a progress-log entry as
  // one commit. Completed stages are skipped on resume — re-running the same
  // prompt picks up from the first incomplete task.
  for task <- plan.tasks do
    stage(s"Task: ${task.title}"):
      session.run(task.description)
      reviewAndFixLoop(                  // runs under this stage
        coderSession = session,
        reviewers = allReviewers(reviewAgent),
        // reviewerSelection defaults to agentDriven(reviewAgent.cheap). Format
        // and lint default to the project's stack settings
        // (`.orca/settings.properties`, auto-discovered on first run) — see
        // "Settings" below.
        task = task.title.value
      )
```

```bash
scala-cli run implement.sc -- "Add a rate-limiter to the /login endpoint"
```

Each flow starts by creating a feature branch, named by a short
cheap-model-generated label derived from the prompt (slugged; pass
`branchNaming = ...` to override). This flow opens no PR, so on success you're
left on the feature branch, ready to test or open a PR by hand — see [The flow
lifecycle](#the-flow-lifecycle) for the full success/failure/resume behavior.

If the flow is interrupted — user intervention or an intermittent error — just
run the same command again: it resumes from the last committed set of changes,
so only a small amount of work is repeated. Orca borrows ideas from durable
computing: which stages have completed, and with what results, is tracked in a
progress file committed alongside the modified code, making commits the unit
of atomicity — the progress log can't drift from the changes in the
repository. When the flow is done, the progress log is removed from the branch
in one last commit.

There are two runnable examples under [`examples/runnable/`](examples/runnable/):
* [01-simple](examples/runnable/01-simple/) (in-memory plan + review, autonomous
  planner),
* [02-interactive](examples/runnable/02-interactive/) (same shape as 01, but the
  planner can ask clarifying questions via `ask_user`).

More flow scripts — `epic.sc`, `issue-pr.sc`, `issue-pr-bugfix.sc`,
`implement-enhanced.sc` — live in [`examples/`](examples/); run them against
your own git repo.

For convenient editing of Orca flow scripts, with code-completion, you can try
the [Metals](https://scalameta.org/metals/) VSCode extension.

## Built-in tools

The following are available inside a `flow(...) { ... }`.

The five coding agents — `claude`, `codex`, `opencode`, `pi`, `gemini` —
share one call surface. Durable: `session(name, seed): FlowSession` →
`.run(prompt)` / `.resultAs[O].run(input)`. One-shot: `run(prompt)`,
`resultAs[O].{autonomous,interactive}.run(input)`. Ephemeral multi-turn:
`chat(): Chat` → `.run(prompt)` / `.resultAs[O]...run(input)`. Common tuning:
`withModel`, `withCheapModel`, `withConfig`, `withSystemPrompt`, `withName`,
`withReadOnly`, `withNetworkOnly`, `withSelfManagedGit`. The table lists each
backend's model accessors and backend-specific extras:

| Tool | Backend-specific methods | Purpose |
|---|---|---|
| `claude` | `haiku`/`sonnet`/`opus`/`fable`, `cheap` (→ haiku), `withModel(Model)`, `withNetworkTools` | Claude Code coding/reviewing agent. Bare `claude` is **Opus with the 1M-token context window** (the long-lived implementer; reviewers share it); use `claude.sonnet`/`claude.haiku` for cheap one-shot calls, or `claude.fable` for the hardest ones. `interactive` mode lives only on `resultAs[O]`. See [Sessions](#sessions) for durable (`session`) vs ephemeral (`run`/`chat`). |
| `codex` | `mini`, `cheap` (→ mini), `withModel(Model)` | OpenAI Codex coding/reviewing agent. |
| `opencode` | `anthropicOpus`/`anthropicSonnet`/`anthropicHaiku`, `openaiSol`/`openaiTerra`/`openaiLuna`, `cheap` (provider-matched: openai→luna, else anthropicHaiku), `withModel(providerModel)` / `withModel(provider, modelId)` | [OpenCode](https://opencode.ai) coding/reviewing agent, driven over HTTP+SSE against a headless `opencode serve` (started lazily, shared for the run; sessions survive it — see [Sessions](#sessions)). Spans providers, so models are provider-qualified: use an accessor (`opencode.openaiLuna`) or `opencode.withModel("openai/gpt-5-mini")` / `opencode.withModel("ollama", "llama3.1")`. Inherits the user's configured `opencode` providers/auth. |
| `pi` | `withModel(Model)` | [Pi](https://pi.dev/) coding agent backend, driven through `pi --mode rpc`. Pi handles provider/model selection through its own CLI configuration; pin a model with `pi.withModel(Model("provider/model"))`. Interactive calls can ask clarifying questions via Orca's `ask_user` bridge. |
| `gemini` | `flash`, `cheap` (→ flash), `withModel(Model)` | Google Gemini CLI coding/reviewing agent, driven via `gemini --output-format stream-json`. Bare `gemini` pins **Gemini 2.5 Pro**; use `gemini.flash` for cheaper one-shot calls. Structured output is prompt-enforced (Gemini has no schema flag); `withReadOnly` maps to `--approval-mode plan`. See [ADR 0015](adr/0015-gemini-stream-json-driver.md). |
| `git` | `createBranch`, `checkout`, `ensureClean`, `commit`, `forceAdd`, `push`, `currentBranch`, `diff`, `diffVsBase`, `defaultBase`, `log`, `resetHard`, `deleteBranch`, `addWorktree`, `removeWorktree`, `listWorktrees`, `diffBranchExcludingOrca` | Git operations against the working tree. Recoverable failures (`BranchAlreadyExists`, `BranchNotFound`, `NothingToCommit`, `PushFailure` — `NonFastForward`/`RemoteDeclined` — `WorktreeAddFailed`, `WorktreeNotFound`) surface as `Either`; `.orThrow` converts a `Left` back to an exception when the case is unexpected. `forceAdd`, `resetHard`, `deleteBranch` are used by the flow runtime for bookkeeping and teardown. |
| `gh` | `createPr`, `updatePr`, `readIssue`, `readIssueComments`, `readPrComments`, `writeComment(pr, body)` / `writeComment(issue, body)`, `upsertComment(pr, marker, body)` / `upsertComment(issue, marker, body)`, `buildStatus`, `waitForBuild` | GitHub PR + CI integration via the `gh` CLI. `createPr` is idempotent by branch (returns the existing PR if one is open); `upsertComment` finds a prior comment carrying `marker` and edits it in place (see [Authoring rules](#authoring-rules) for the re-run pattern). `updatePr` replaces a PR's title + body. `waitForBuild` returns `Either[BuildWaitFailed, …]`. |
| `fs` | `read`, `write`, `list` | Working-tree file I/O. `read` returns `Option[String]` so a missing file is a branch point, not an exception. |

The runtime owns git: every write-capable agent turn is told not to commit,
push, or switch branches — it edits the working tree, and the flow
commits/branches/pushes via `git.*`. This keeps `reviewAndFixLoop`'s diff-based
reviewer selection working (a self-committing agent would leave an empty
`git.diff()`). Opt out per-tool with `claude.withSelfManagedGit` (mirrors
`withReadOnly`).

For the LLM interfaces, `resultAs[O]` defines the shape of the structured
output. The `O` type needs a `JsonData[O]` (provided by `derives JsonData` on a
case class) for schema generation and deserialization. Additionally, you might
define an `Announce[O]` so that a friendly summary is printed in the event log,
instead of a raw json.

A minimal Pi-backed flow looks the same; Pi reads your normal Pi configuration
and is driven through RPC mode under the hood:

```scala
flow(OrcaArgs(args)):
  val session = pi.session("run", seed = userPrompt)
  stage("Run"):
    session.run(userPrompt)
```

## Coding agent tools

There are two ways to drive a model in a flow:

- **The role agents — `planningAgent`/`codingAgent`/`reviewAgent`.**
  Backend-agnostic: each is resolved from settings (see
  [Settings](#settings)), defaulting to claude. Use `planningAgent` for
  `Plan.*` calls, `codingAgent` for the implementer's durable session, and
  `reviewAgent` for `allReviewers(...)` and the review machinery's defaults.
  Edit settings and the whole flow follows; you never name a backend in the
  body.
- **A specific agent + model — `claude.opus`, `codex.mini`, `opencode.openaiLuna`.**
  Use a concrete accessor when you want a particular backend or tier
  regardless of settings — `epic.sc` pins claude to implement and codex to
  review, for instance. The tier accessors (`.opus`/`.sonnet`/…) live on the
  concrete agents, not on the role accessors — so `codingAgent.opus` won't
  compile; that's the cue to name the backend. Pin any other model with
  `withModel(Model("…"))`. Don't mix the two for one session (a `SessionId`
  is backend-typed).

> [!WARNING]
> **Coding agent tool usage is auto-approved by default** (`tools =
> ToolSet.Full`, `autoApprove = AutoApprove.All`): write-capable turns let the
> agent edit files and run shell commands without prompting. Constrain this in
> code, or isolate the whole run in a sandbox.

Two axes constrain an agent. **Capability** (`AgentConfig.tools: ToolSet`) is
which tools exist at all:

```scala
// ReadOnly — reads only, no shell, no edits (reviewers, plan review, brief).
val reviewer = claude.withReadOnly

// NetworkOnly — reads plus read-only network (web + GitHub), for planners that
// must read an issue/PR. Hard no-edit on claude (command-scoped `--allowedTools`,
// configurable via `claude.withNetworkTools(...)`), gemini (scoped `web_fetch`),
// and opencode (web); on pi/codex network needs a writable shell, so there the
// no-edit guarantee is prompt-only. See ADR 0016.
val planner = claude.withNetworkOnly

// Full (the default) — write-capable.
```

**Prompting** (`autoApprove`) is which of the available tools auto-approve
without a y/n prompt — only meaningful for interactive turns, and consulted
only on `Full`:

```scala
// Restrict auto-approval to a named tool set (honoured by claude).
val limited = claude.withConfig(
  AgentConfig(autoApprove = AutoApprove.Only(Set("Read", "Edit", "Grep")))
)
```

`AutoApprove.Only` fits interactive flows, where a human answers anything outside
the set; an autonomous turn has no one to approve, so an out-of-set call blocks.
Only claude enforces the set per tool — codex and gemini have no per-tool
granularity, so there `Only` widens to full auto-approve. For an unattended run
the practical boundary is a sandbox: [Sandcat](https://github.com/VirtusLab/sandcat),
[Docker Sandboxes](https://docs.docker.com/ai/sandboxes/), or any other.

## Flow methods

Top-level, available via `import orca.*`:

| Method | Signature | Use |
|---|---|---|
| `flow(args, ...)(body)` | `flow(args: OrcaArgs, branchNaming?, stackSettings?, planningAgent?, codingAgent?, reviewAgent?, returnToStartBranch = false, progressStore?)(body)` | Entry point. Creates one feature branch + one progress log for the run. The three role agents (below) resolve from settings — see [Settings](#settings) — defaulting to claude; `planningAgent`/`codingAgent`/`reviewAgent` here are per-role programmatic overrides (`Some(_.claude.opus)`) that win over both settings files. Branch naming defaults to a short cheap-model-generated label (slugged); pass `branchNaming = Some(BranchNamingStrategy.issue(handle))` to override (e.g. for issue flows). `stackSettings = Some(StackSettings(...))` pins the run's [stack settings](#settings) — the settings file's stack portion is then neither read nor written (the escape hatch for a language-specific flow; its agent keys are still honoured). See [The flow lifecycle](#the-flow-lifecycle) for the full branch/teardown behavior. |
| `planningAgent` (in-body accessor) | `planningAgent: Agent[ctx.PlanB]` | The planning-role agent, resolved from settings — see [Coding agent tools](#coding-agent-tools). Hand it to `Plan.*`. |
| `codingAgent` (in-body accessor) | `codingAgent: Agent[ctx.CodeB]` | The coding-role agent — the run's primary: implementer sessions, branch naming, stack discovery, default commit messages. |
| `reviewAgent` (in-body accessor) | `reviewAgent: Agent[ctx.ReviewB]` | The review-role agent: `allReviewers(reviewAgent)`, the reviewer-picker and the lint summariser default to its tiers. |
| `stage[T: JsonData](name, commitMessage?)(body)` | `(name: String, commitMessage: Option[T => String] = None)(body): T` | The committing, resumable unit of work. On success, records the result, force-adds the progress log, and commits (code changes + log delta = one commit). On re-run, a stage whose result is still recorded is skipped and the stored value is returned. `T` must have `JsonData` — `case class Foo(...) derives JsonData` is enough. Commit message defaults to a `codingAgent.cheap` summary of the diff; override via `commitMessage`. |
| `display(message)` | `(message: String): Unit` | Progress-only output: no stage, no commit, no log entry. Callable anywhere — outside a stage or inside a fork. |
| `Par.mapUnordered(n)(items)(f)` | `(parallelism: Int)(items: Seq[A])(f: A => R): List[R]` | The sanctioned script fan-out (no Ox import needed). Ephemeral agent turns (`codingAgent.run`, `chat.run`) work inside `f`; the durable, flow-thread-only operations (`stage`, `codingAgent.session`, `session.run`) throw if called from a fork. Results arrive in completion order. |
| `fail(message)` | `(message: String): Nothing` | Abort with a message. Triggers failure teardown: stays on the feature branch so a re-run resumes. |

### Overriding tools and agents

Any tool or agent `flow(...)` builds by default can be replaced by a named
argument. Plain tools take the value directly (`git = Some(myGit)`,
`interaction = Some(myInteraction)` — your own `orca.backend.Interaction`
implementation, e.g. for Slack; not exported from `orca.*`, so import it by
its full path). Agents take a **factory** that
receives the run's `AgentWiring` (event sink, interaction, workDir, prompts), so
a custom agent lands on the same dispatcher as the defaults:

```scala
// Start from a per-backend factory and tune it:
flow(OrcaArgs(args), claude = Some(w => ClaudeAgents.default(w).opus))
// …or wrap a prebuilt agent:
flow(OrcaArgs(args), claude = Some(_ => myAgent))
```

Factories exist for all five backends: `ClaudeAgents.default(w)`,
`CodexAgents.default(w)`, `GeminiAgents.default(w)`, `PiAgents.default(w)`, and
`OpencodeAgents.default(w, launcher)` — opencode's factory is applied where the
run's `Ox` scope exists (it pins a shared `opencode serve` to the scope), so its
slot is typed `AgentWiring => Ox ?=> OpencodeAgent`.

### Side effects happen inside stages

Every side-effecting call — git mutations (`commit`/`push`/`resetHard`/…),
`fs.write`, `gh` writes, every `agent.*.run` — must happen inside a `stage`
body, and **the compiler enforces it**: a mutation outside a stage doesn't
compile. Pure reads (`git.diff`, `git.log`, `gh.readIssue`, `fs.read`),
`display`, and `fail` run anywhere; `agent.session(name, seed)` runs outside a
stage too — it records a session, not a side effect. Where to *place* effects is
covered by the [Authoring rules](#authoring-rules).

### The flow lifecycle

Each `flow(...)` run is bound to exactly one feature branch and one progress
log (`.orca/progress-<hash>.json`, where `<hash>` is derived from the prompt):

- **Start:** stash a dirty working tree with a warning (recover with `git stash
  pop`); create + checkout the feature branch; write and commit the progress log
  header.
- **Resume:** the progress log lives at a branch-independent, prompt-derived path,
  so recovery finds it before any checkout. Its header is validated as untrusted
  input (branch must match orca naming rules, prompt hash must match), then the run
  resumes from the first incomplete stage. A corrupt or truncated progress log is
  detected at startup — orca warns and starts fresh (previous stages re-run)
  rather than silently mis-resuming.
- **Success teardown:** remove the progress-log file in a final commit. A
  throwaway feature branch (no substantive changes vs the starting branch) is
  deleted and HEAD returns to the starting branch. Otherwise the feature branch
  is kept and HEAD **stays on it by default** (so you end on the work); pass
  `returnToStartBranch = true` — for flows that open a PR — to return HEAD to the
  starting branch instead.
- **Failure teardown:** discard the failed stage's uncommitted partial edits with
  `git reset --hard`; stay on the feature branch so a re-run resumes in place.

### Settings

Two files, both plain `key = value` lines, parsed once per run before setup:

- **`{workDir}/.orca/settings.properties`** — committed, hand-editable
  project settings: the stack commands (`format`/`lint`/`test`) and, per role,
  which agent to use.
- **`$XDG_CONFIG_HOME/orca/settings.properties`**, defaulting to
  `~/.config/orca/settings.properties` (the XDG Base Directory spec — the
  `gh`/`git` CLI convention, followed on macOS too) — a per-user default,
  agent keys only. A relative or unset `XDG_CONFIG_HOME` falls back to
  `~/.config`; an absent global file is simply skipped.

Per role key, precedence is: the `flow(planningAgent = ..., codingAgent =
..., reviewAgent = ...)` programmatic override > the project file > the
global file > the built-in default (claude, no model pin). Stack commands
follow a separate chain (unchanged from ADR 0019):
`reviewAndFixLoop(formatCommands = Use(...)/Off)` > `flow(stackSettings =
Some(...))` > the project file > auto-discovery (which writes the file). An
unreadable or malformed file — project or global — aborts the run before any
tree mutation; the global file may contain ONLY agent keys, so a stack key
there is also an error.

**Stack commands.** Keys `format`, `lint`, and `test`. Each value is one shell
command, run via `bash -c` in the flow's working directory; everything after
the first `=` is command text (`lint = FOO=bar cargo check` works). Repeating
a key appends — the task's commands run in file order, so a multi-stack repo
lists one line per stack half. A `#` line is a comment; discovery places each
command's evidence as its own `#` line directly above the `key = command`
line. A missing or commented-out key means the task is disabled (its gate is
skipped). A typical discovered project file:

```properties
# orca settings — edit freely, commit with the project.
# Delete the stack lines (format/lint/test, commented ones too) to re-run auto-discovery.
# Cargo.toml; via rustfmt
format = cargo fmt
# Cargo.toml
lint = cargo check --tests
# test =   (no test config found)
```

**Agent keys.** `planningAgent`, `codingAgent`, and `reviewAgent`, valid in
both files, single-valued (a repeated agent key is an error). Value grammar:
`harness[:model]`, split at the first `:` so a model id containing `:`
survives; `harness` is one of `claude`, `codex`, `opencode`, `pi`, `gemini`
(an unrecognised name is an error naming the valid set). The model part is
passed **verbatim** to the harness's `withModel` — orca does not normalise or
validate model ids — for example:

```properties
planningAgent = claude:opus
codingAgent = codex:gpt-5-mini
reviewAgent = opencode:anthropic/claude-haiku-4-5
```

Agent keys are read even when `flow(stackSettings = Some(...))` overrides the
stack commands — that override governs the stack portion only, and a
malformed project or global file still aborts the run either way. `setup`
announces the resolved roles and where each came from:

```text
agents: planning=claude (default), coding=codex:gpt-5-mini (project), review=claude (global)
```

**Auto-discovery.** Discovery runs when the project file is absent, or when
it exists but names no stack line (live or commented) — a hand-written file
containing only agent keys still triggers it, appending the discovered stack
entries below the existing content rather than overwriting it, so agent lines
are never touched. Delete the stack lines (or the whole file) to re-run
discovery. When it runs (and no `flow(stackSettings = ...)` override is
passed), the first run spends one cheap-model, read-only agent call
inspecting the repo, then writes the file and announces every guess in the
event log:

```text
no .orca/settings.properties — running stack discovery
  format = cargo fmt   # Cargo.toml; via rustfmt
  lint = cargo check --tests   # Cargo.toml
warning: stack settings: no test command — gate disabled
written to .orca/settings.properties — review and edit as needed.
```

Every discovered command must cite the file that evidences it; two mechanical
checks run before anything is written — the command's executable must be on
`PATH`, and the cited evidence file must exist — and a command failing either
check is demoted to a commented line with its reason (e.g. `# lint = just
check   (just: not found on PATH)`), never run silently. A discovery failure
(backend unavailable, invalid output) aborts the run — it is never degraded
into a written "gates off" file. Runs with an existing, stack-complete file —
the steady state, including CI — make no model call.

**The `.orca/` directory** is committed by default: settings and the
progress log ride the branch, while scratch files live under `.orca/cache/`,
which writes its own `.gitignore` so it can never land in a commit. If your
`.gitignore` covers all of `.orca/`, every run warns: `stack settings at
.orca/settings.properties are gitignored — remove the '.orca/' line from
.gitignore so they can be committed (scratch self-ignores under
.orca/cache/)`. Do what the warning says — the cache stays ignored on its
own.

Within a flow body the resolved stack settings are available as
`summon[FlowContext].stackSettings` — a `StackSettings(format, lint, test:
List[String])`. The `test` commands are not consumed by `reviewAndFixLoop`
(the lint gate stays deliberately cheap); they're there for a flow's own
verification stages.

### Sessions

Three rungs, by how long the conversation must live — the handle you hold
tells you which one you're on:

| Call site | Kind | Survives crash/resume | Runs in a fork |
|---|---|---|---|
| `agent.run(prompt)` | one-shot | no | yes |
| `agent.chat()` → `chat.run(prompt)` | ephemeral multi-turn | no | yes |
| `agent.session(name, seed)` → `session.run(prompt)` | durable | yes (resumable identity; re-seeded if the backend lost the conversation) | no |

The rule: **name + seed ⇒ durable; anonymous ⇒ gone on crash.** Structured
output mirrors it (`agent.resultAs[O].{autonomous,interactive}.run(input)`,
`chat.resultAs[O]...`, `session.resultAs[O].run(input)`), and `interactive`
exists only on the ephemeral rungs — a live human steering a turn can't be
replayed from a seed, so durable interactive sessions don't exist by
construction.

- **Durable — `agent.session(name, seed)`.** A get-or-create keyed by `name` +
  occurrence, stage-style: it reserves a `SessionId` and records `(name,
  seed)` in the progress log (no LLM call), then returns a `FlowSession`
  handle bundling the agent with that id, so the same `name` resumes the same
  session — and the handle survives a flow crash/resume — across re-runs.
  Inserting or reordering *other* `session(...)` calls between runs doesn't
  re-key this one — only the call order among sessions sharing this `name`
  matters (to disambiguate duplicates of the same name). On resume the
  recorded session is reused; if the recorded seed for that name differs from
  this call's, orca warns and reuses the recorded session rather than
  silently resuming the wrong one. Recording a session isn't a side effect,
  so `agent.session(...)` is callable outside a stage — and it MUST be (the
  compiler rejects an in-stage mint); its runs happen inside stages, on the
  flow thread only.
- **Ephemeral — `agent.chat()`.** A `Chat` handle continuing one conversation
  across `.run` calls *within this run only* — no seeding, no persistence.
  Runs need only the shared `InStage` capability, so chats work inside a
  `Par.mapUnordered` fork: parallel reviewers each holding a multi-turn
  conversation is the canonical use. `agent.chat(session.id)` adopts a durable
  session's conversation as an ephemeral chat — the escape hatch for
  follow-ups from a fork (turns are not persisted; one live continuation at a
  time).

```scala
val session = agent.session("implementer", seed = plan.brief)
session.run(task.description)

val chats = Par.mapUnordered(4)(reviewers): r =>
  val c = r.chat()
  c.run(s"review the diff: $diff")
  c                       // keep the conversation for a later re-review turn
```

The `seed` is the essential context to rebuild the agent — typically the **plan
brief**, or the issue body when there is no brief. `FlowSession.run` (and its
structured sibling `resultAs[O].run`) primes a fresh session with
the seed on first use; if the backend session is lost on resume it re-seeds
(with a console warning — the prior conversation history is gone, only seed +
progress preamble are rebuilt), prepending a progress preamble naming the
completed stages; if the session is still alive it continues it directly with
its full history. Note: opencode sessions survive a process restart too —
opencode persists them in its own global on-disk store, independent of orca's
per-run `opencode serve` process. As with every backend, that holds on the
same machine with the backend's store intact; otherwise the flow re-seeds
safely (the uniform fallback).

`agent.cheap` returns the backend's cheap/fast variant (claude → haiku, codex →
mini, gemini → flash, opencode → anthropicHaiku, others → self) — used by the
runtime for branch naming and default commit messages.

**Backend swaps across runs.** A recorded session is tagged with the backend
that minted it. If a settings edit changes a role's agent between runs (e.g.
`codingAgent = codex` becomes `codingAgent = claude`), the next run finds a
session recorded under the old backend: rather than resume it against the
new, unrelated backend, orca mints a fresh session and warns
(`warning: session '<name>' #<n> was minted on <old>; this agent is <new> —
minting fresh`) — the same re-seed fallback that already covers a lost
backend conversation.

## Authoring rules

Mutations outside a stage body are compile errors (see [Side effects happen
inside stages](#side-effects-happen-inside-stages)). The rules below are the
structural conventions you choose to follow as a flow author.

1. **Reads outside, mutations inside.** Only side-effecting work goes in a
   stage. Pure reads (`git.diff`, `gh.readIssue`, `fs.read`, `gh.waitForBuild`)
   run outside stages — staging them wastes commits and checkpoints.
   `agent.session(name, seed)` also belongs outside stages (see
   [Sessions](#sessions)).

2. **Push lives in a later stage than the edit that produced it.** A stage
   commits only on completion: a `git.push()` in the same stage as the edit would
   push nothing (the edit isn't committed yet). The push must be in a *separate,
   later* stage:

   ```scala
   stage("Write failing test"):
     session.run("Write the failing test …")    // commits on completion

   val pr = stage("Push + open PR"):   // LATER stage — the test commit exists now
     git.push().orThrow
     gh.createPr(title = …, body = …).orThrow
   ```

3. **One commit per stage.** Each stage produces exactly one commit (code
   changes + the progress-log entry). Don't call `git.commit` inside a stage
   body — the runtime commits for you when the stage completes.

4. **Idempotent external effects, each in its own stage.** Put each PR-open,
   comment-post, or push in a dedicated stage so it's checkpointed. `gh.createPr`
   is idempotent by branch (an open PR is reused, not duplicated) and
   `gh.upsertComment(target, marker, body)` edits a prior comment carrying
   `marker` in place — so if a crash re-opens the stage on resume, the re-run
   reuses the PR/comment instead of duplicating it. Use
   `orcaCommentMarker(userPrompt, purpose)` so the marker is unique to this run.

5. **Name stages descriptively.** The stage name appears in the event log,
   the commit message (when no override is provided), and the progress preamble
   on resume. A name like `"Push + open PR"` lets a reader (and the resuming
   agent) understand the checkpoint without reading code.

## Experimental: capabilities & compile-time concurrency checking

Orca gates side effects behind three capability tokens. You normally never
construct one — `stage(...)` bodies provide them, and a missing token is a
compile error with a message telling you where the call belongs:

| Capability | Kind | Gates | Provided by | Misuse caught by |
|---|---|---|---|---|
| `InStage` | shared (`caps.SharedCapability`) | LLM runs (`agent.*.run`, `session.run`) | `stage(...)` bodies | missing-given compile error |
| `WorkspaceWrite` | exclusive (`caps.ExclusiveCapability`) | git/`gh` writes, `fs.write`, progress-log writes | `stage(...)` bodies | missing-given compile error; must never cross a `fork` |
| `FlowControl` | exclusive (`caps.ExclusiveCapability`) | starting stages, minting sessions | the `flow(...)` body (not forks) | missing-given compile error + a runtime owner-thread check |

(`FlowContext` — reads and event emission — is deliberately *not* a
capability: it is thread-safe and forks receive it freely.)

The shared/exclusive split is [Scala's experimental capture
checking](https://docs.scala-lang.org/scala3/reference/experimental/cc.html)
vocabulary, and enforcement comes in three layers:

1. **Always on, no setup:** the runtime guards — a fork that calls
   `stage(...)`/`session(...)` fails immediately with the rule it broke; a
   second `flow(...)` in the same working tree is refused; an agent used
   after its flow ended throws.
2. **Always on, inside the library:** orca's own parallel code (the reviewer
   fan-out) is compiled under capture + separation checking — a change that
   captured a `WorkspaceWrite` into that fan-out would not compile, and a
   compile-time test suite pins both the rejections and the deliberately
   legal `InStage` capture.
3. **Opt-in, in your script:** add the two language imports to a `.sc` file
   to have the compiler check *your* code too — today that enforces, e.g.,
   that a custom `ReviewerSelector`'s per-round function stays pure (effects
   belong in `prepare`):

   ```scala
   import language.experimental.captureChecking
   import language.experimental.separationChecking
   ```

   Full compile-time fork-boundary checking in scripts (rejecting a
   `WorkspaceWrite` captured into a raw `ox.fork`) arrives when Ox itself
   adopts capture checking; until then layer 1 covers that case at runtime.

Capture checking is an evolving experimental compiler feature. The imports
cost nothing when omitted — scripts without them compile and run identically
— and orca tracks the feature's development (see ADR 0018 §6 for the design
record).

## Planning utilities

Available via `import orca.plan.*`:

The planning entry points form a **mode × operation grid**. The two axes are
orthogonal — every combination is valid. Mode is picked at the call site
(`Plan.autonomous.*` vs `Plan.interactive.*`), mirroring how `Agent` itself
splits `autonomous` / `interactive`:

| Operation | Result | `autonomous` (read-only + network, no human) | `interactive` (agent can `ask_user`) |
|---|---|---|---|
| `from(userPrompt, agent, instructions?)` | `Plan` | plan in one agentic turn | drive the planner conversationally |
| `assessThenPlan(userPrompt, agent, instructions?)` | `Verdict[Plan]` | assess, then `Proceed(plan)` or `Rejection(kind, body)` | same, but can ask the reporter to clarify instead of rejecting |
| `triage(report, agent, instructions?)` | `Triage` | classify a bug report (not-a-bug / untestable / testable) | same, with clarifying questions |

Every cell returns `Sessioned[B, <result>]` — the result paired with the
(ephemeral) `Chat` that produced it. Continue that conversation in-run
(`chat.run(task)` — the planning turn ran restricted, but the chat is bound to
the base agent, so continuations have write access), or `.value` it and get a
fresh, durable implementer session via `agent.session("implementer", seed =
plan.brief)` — the chat does not survive a crash/resume, so every shipped
example takes `.value`. Destructure positionally when you want both:
`val Sessioned(chat, plan) = Plan.autonomous.from(...)`.

From a `Sessioned[B, Plan]`, an optional `.reviewed(agent)` step refines the plan
before implementing — the planner critiques its own draft, producing an improved
`Plan`. Chain it: `Plan.autonomous.from(...).reviewed(claude).value`.

`assessThenPlan` returns a `Verdict`: `Verdict.Proceed(plan)` to implement, or
`Verdict.Rejection(kind, body)` — a follow-up question, critique, or rebuff the
caller surfaces back to the reporter. `triage` returns a `Triage` sum type the
caller pattern-matches (`NotABug` / `Untestable` / `Testable`).

Review utilities, available via `import orca.review.*`:

| Method | Use |
|---|---|
| `lint(commands, agent, instructions?)` | Run shell lint commands (in order, each via `bash -c`; every one runs even if an earlier one fails) and have `agent` summarise their labelled, concatenated output as a `ReviewResult`. Short output is inlined into the prompt; anything larger is written to a file under `.orca/cache/` for the agent to read, so unbounded output can't overflow the context. |
| `reviewAndFixLoop(coderSession, reviewers, task, ..., formatCommands?, lint?, fixInstructions?)` | Run reviewers against `task`, collect findings above the confidence threshold, hand them to the `coderSession` (a `FlowSession`) to fix, re-evaluate. Halts when reviewers come back clean, the fixer marks every remaining issue as won't-fix, or the iteration cap is reached. `formatCommands: Configured[List[String]]` runs before each review round; `lint: Configured[Lint]` runs alongside the reviewers each round — both default to the project's [stack settings](#settings), see below. |
| `allReviewers(base)` | All eight canonical reviewer agents (code-functionality, test, readability, code-structure, simplicity, performance, security, scala-fp) layered on top of `base`. |
| `minimalReviewers(base)` | Universally-applicable subset (code-functionality, readability, test). Pair with the default LLM-driven selector when the full set is overkill. |
| `fixLoop(evaluate, fix, ...)` | Lower-level primitive `reviewAndFixLoop` is built on. |

`reviewAndFixLoop`'s stack-dependent parameters are three-state
(`orca.Configured`), so omission means "from the project's [stack
settings](#settings)" while "explicitly off" stays expressible:

```scala
enum Configured[+A]:
  case FromSettings   // resolve from the run's stack settings (the default)
  case Off            // explicitly disabled for this call
  case Use(value: A)  // explicit value; settings ignored
```

`FromSettings` resolves `formatCommands` to `stackSettings.format` and builds
the lint gate as `Lint(stackSettings.lint, reviewAgent.cheap)` — commands plus
the summariser agent bundled in one value (`Lint(commands: List[String], agent)`).
An empty list resolves to no gate at all: `FromSettings` over empty settings
behaves exactly like `Off`. A script that omits `lint` gets a lint gate
whenever the target project's settings define one; for format-only, pass
`lint = Configured.Off`.

`reviewAndFixLoop`'s `reviewerSelection` defaults to
`ReviewerSelector.agentDriven` — a picker LLM on `reviewAgent`'s cheap tier
sees each reviewer's description plus the changed file paths and narrows the
supplied list per task. Point the picker at a specific model
(`ReviewerSelector.agentDriven(claude.haiku)`), pass
`ReviewerSelector.allEveryRound` to run every reviewer every iteration, or
`ReviewerSelector.onlyPreviouslyReporting` to re-run only the reviewers that
found something last round.

To swap or extend the reviewer set, compose your own `List[Reviewer]` from
`ReviewerPrompts` (the shipped entries, `ReviewerPrompts.all`/`.minimal`, and/or
your own `Reviewer(name, description, systemPrompt)`) and turn it into agents
with `buildReviewers(base, list)`.

PR utilities, available via `import orca.pr.*`:

| Method | Use |
|---|---|
| `summarisePr(agent, diff, context?, instructions?)` | Fold a branch diff into a `PrSummary(title, body)` for `gh.createPr`. `context` is an optional preamble (originating issue link, user prompt, etc.) the model anchors the description to. Use a cheap model (`claude.cheap`, `codingAgent.cheap`). |

### Customising prompts

Every domain helper that bundles an LLM brief takes its prompt as a
default-valued `instructions: String`; the default lives on a sibling
`XxxPrompts` object. Override it, or compose with the default to extend it:

```scala
import orca.plan.{Plan, PlanPrompts}

Plan.interactive.from(
  userPrompt,
  claude,
  instructions = PlanPrompts.Planning + "\n\nPrioritise observability tasks first."
)
```

<details>
<summary>Where the defaults live</summary>

- `orca.plan.PlanPrompts` — `Planning`, `AssessThenPlan`, `Triage`, `Review`
- `orca.pr.PrPrompts` — `Summarise`
- `orca.review.ReviewLoopPrompts` — `Fix`, `SelectReviewers`, `SummariseLint`,
  `ReReview`
- `orca.review.ReviewerPrompts` — per-reviewer system prompts (compose your own
  list to swap or extend `allReviewers`/`minimalReviewers`)

The lower-level per-call wrappers (autonomous/interactive/retry) are a separate
layer — replace the whole set via `flow(prompts = ...)`. See [ADR
0010](adr/0010-prompts-and-helpers-convention.md) for the full convention.

</details>

## Data structures

Common types you'll see in flow scripts. Most `derives JsonData`, making them
valid stage results (the stage log can record and replay them) and usable as
structured LLM output via `claude.resultAs[T]`. Exceptions: `Sessioned` and
`Verdict` do not derive `JsonData` — they are intermediate values, not stage
results.

<details>
<summary>The types, in detail (click to expand)</summary>

- **`orca.plan.Plan(epicId, description, tasks, brief)`** — the task list the
  agent generates in one round-trip. `epicId` is a kebab-case identifier for
  the plan itself (heads its markdown render) — NOT the git branch name; the
  flow derives and announces its own branch separately (see
  [`BranchNamingStrategy`](#the-flow-lifecycle)). `description` is the
  planner's epic summary; `brief` is a concise codebase briefing always
  included (feed it to `agent.session("implementer", seed = plan.brief)`, which threads it as
  the seed). `taskPrompt(task)` prepends the brief to a task's description.
- **`orca.plan.Task(title, description)`** — `title` is the
  human-readable label shown in the event log.
- **`orca.plan.Sessioned(chat, value)`** — every `Plan.{autonomous,
  interactive}.*` operation returns one: the result paired with the (ephemeral)
  `Chat` that produced it, so the caller can continue that conversation in-run
  or `.value` it and start fresh.
- **`orca.plan.Verdict[A]`** — `Verdict.Proceed(value)` or
  `Verdict.Rejection(kind, body)` (kind ∈ Question / Critique / Rebuff).
  Returned by `assessThenPlan` as `Verdict[Plan]`.
- **`orca.plan.Triage`** — sum type returned by `triage`: `NotABug`,
  `Untestable`, or `Testable` — each carrying exactly the fields its branch
  needs.
- **`orca.plan.BugReportMatch`** — the agent's decision on whether a CI failure
  matches the original report.
- **`orca.FlowSession[B]`** — durable, resumable session handle returned by
  `agent.session(name, seed)`. Bundles the agent with its `SessionId`; call
  `.run(prompt)` or `.resultAs[O].run(input)` on it to drive the
  agent, with automatic seed/preamble replay (when the backend conversation
  isn't live) and resume-wire-id persistence. `agent.chat(session.id)` adopts
  its conversation as an ephemeral `Chat` (the fork-side escape hatch).
- **`orca.agents.Chat[B]`** — ephemeral multi-turn conversation handle from
  `agent.chat()`: tool-using and workspace-editing like any agent turn
  ("chat" names its lifetime, not its powers), in-run only, fork-safe. Also
  carried by `Sessioned` for planning-conversation continuations.
- **`orca.agents.SessionId[B]`** — typed session id, parameterised by backend,
  exposed via `FlowSession.id`. Carries the backend identity at the type
  level, so you cannot accidentally pass a Claude session to Codex.
- **`orca.Title`** — opaque `String` alias for short labels (`Task.title`,
  `ReviewIssue.title`); `Title("…")` to construct, `.value` to read.
- **`orca.tools.PrHandle(owner, repo, number)`** — handle to an open pull
  request, returned by `gh.createPr`. `derives JsonData` so a stage can record
  it: a push-and-open-PR stage is the checkpoint before a CI wait.
- **`orca.pr.PrSummary(title, body)`** — what `summarisePr` returns. The two
  fields feed `gh.createPr(title = …, body = …)` directly.
- **`orca.review.ReviewIssue` / `ReviewResult`** — what reviewer agents return.
  Issues carry severity, confidence, a `title` (shown), and a long
  `description` (sent to the fixer).
- **`orca.review.FixOutcome(fixed, ignored)`** — what the fix step returns:
  the titles of issues actually fixed in code, plus titles + reasons for
  issues set aside (environmental, out of scope, false positive). The loop
  re-evaluates iff `fixed` is non-empty.
- **`orca.review.IgnoredIssues`** — accumulated `IgnoredIssue(title, reason)`
  entries surfaced by `reviewAndFixLoop` once it halts.
- **`orca.StackSettings(format, lint, test)`** — the resolved per-project
  tooling commands (each field a `List[String]`, run via `bash -c`; empty =
  task disabled). Resolved once per run — see [Settings](#settings) — and
  read back via
  `summon[FlowContext].stackSettings`; pass `flow(stackSettings = Some(...))`
  to pin it.
- **`orca.Configured[A]`** — three-state default for `reviewAndFixLoop`'s
  stack-dependent parameters: `FromSettings` (the default — resolve from the
  run's stack settings), `Off` (explicitly disabled for this call), or
  `Use(value)` (explicit value; settings ignored).
- **`orca.review.Lint(commands, agent)`** — the lint gate bundle
  `reviewAndFixLoop` runs alongside the reviewers: the shell commands plus the
  (cheap) agent that summarises their output into a `ReviewResult`.

</details>

## Output

While Orca runs the terminal output is split into two zones: an **event log**
that grows top-to-bottom as stages and tools fire, and a **status line** pinned
to the bottom, showing the active stage breadcrumb with a spinner. Nested stages
are indented.

<details>
<summary>Glyph legend</summary>

| Glyph | Meaning |
| ----- | ------- |
| `▶` | Stage start, or a `Step` (single-line note like a branch switch) |
| `▸` | User's prompt at the start of an interactive session |
| `●` | Assistant prose |
| `⏺` | Tool call (path / command / query in grey) |
| `⎿` | Tool result (truncated to one line) |
| `✖` | Error |
| `?` | Approval request |

</details>

Colours and animation auto-disable when stderr isn't a terminal. Set
`NO_COLOR=1` or `ORCA_NO_ANIMATION=1` (suppresses the spinner) to force them
off.

## Authenticating the coding agents

Each CLI manages its own auth; Orca stores no secrets. Before running a flow,
log in to the backend you use — `claude`, `codex`, `opencode`, or `pi` — and to
`gh` (for the GitHub helpers), each per its own instructions.

<details>
<summary>OpenCode with a local Ollama model</summary>

- **Launcher (zero config):** `flow(OrcaArgs(args), opencode =
  Some(w => OpencodeAgents.default(w, OpencodeLauncher.ollama("qwen3-coder"))))`.
  Orca starts the server via `ollama launch opencode`, which injects Ollama's
  provider config and pins that one model — use bare `opencode`, no
  `withModel`. Needs the `ollama` CLI and the model pulled.
- **Manual config:** declare an `ollama` provider in
  `~/.config/opencode/opencode.json` (baseURL `http://localhost:11434/v1`, your
  models, `num_ctx` raised for tool use), then `opencode.withModel("ollama",
  "qwen3-coder")`. Supports several models and per-turn switching.

</details>

## Getting set up

Orca is published to Maven Central — `scala-cli` fetches the artifacts on first
run:

```bash
scala-cli run implement.sc -- "your task here"
```

## Documentation

- [`adr/`](adr/) — architecture decision records. [ADR
  0018](adr/0018-stage-bound-flow-runtime.md) describes the current stage-bound
  runtime; the ADR index covers module layout, backends, the flow DSL, and
  reviewers.
- [`AGENTS.md`](AGENTS.md) — internals, conventions, build/test recipes; the
  same file AI assistants pick up.

## License

Apache 2.0 — see [LICENSE](LICENSE).

## Copyright

Copyright (C) 2026 VirtusLab [https://virtuslab.com](https://virtuslab.com).
