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

You can use Orca to orchestrate development in any language and ecosystem.

Orca assumes that it has configured, logged-in access to Claude, Codex,
OpenCode, or Pi (depending which backend you use), as well as `gh` and `git`.

## An example flow

Save this as `implement.sc` and run it with your task:

```scala
//> using scala 3.8.4
//> using dep "org.virtuslab::orca:0.0.14"
//> using jvm 21

import orca.{*, given}

// `_.claude` selects the leading agent; in the body reference it as `agent`,
// not `claude` — backend-agnostic, so switching the selector switches the whole
// flow (details under "Coding agent tools").
flow(OrcaArgs(args), _.claude):
  // `stage` is the committing, resumable unit of work. The plan is produced in
  // one agentic turn and recorded in the stage log; a re-run with the same
  // prompt skips this stage and reads the stored Plan back.
  val plan = stage("Plan"):
    Plan.autonomous.from(userPrompt, agent).value  // .value takes the Plan, discarding the planner's session

  // Get-or-create the implementer session, seeded with the plan's brief
  // (primes it on first use, replayed if the backend session is lost on
  // resume) — one call in place of `agent.session("implementer", seed = ...)`.
  val session = agent.session("implementer", seed = plan.brief)

  // One stage per task: each stage commits its work + a progress-log entry as
  // one commit. Completed stages are skipped on resume — re-running the same
  // prompt picks up from the first incomplete task.
  for task <- plan.tasks do
    stage(s"Task: ${task.title}"):      // skipped on resume if already done
      session.run(task.description)
      reviewAndFixLoop(                  // runs under this stage
        coderSession = session,
        reviewers = allReviewers(agent),
        // reviewerSelection defaults to agentDriven(agent.cheap).
        task = task.title.value,
        // Format after every edit so commits stay formatted and reviewers
        // skip style nits.
        formatCommand = Some("cargo fmt"),
        // Cheap sanity gate; correctness is the reviewers' and CI's job.
        lint = Some(Lint("cargo check --tests", agent.cheap))
      )
```

```bash
scala-cli run implement.sc -- "Add a rate-limiter to the /login endpoint"
```

The feature branch name defaults to a short cheap-model-generated label
(slugged); pass `branchNaming = ...` to override. This flow opens no PR, so on
success you're left on the feature branch, ready to test or open a PR by hand
— see [The flow lifecycle](#the-flow-lifecycle) for the full success/failure/
resume behavior.

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

The following are available inside a `flow(...) { ... }`:

| Tool | Methods | Purpose |
|---|---|---|
| `claude` | Durable: `session(name, seed): FlowSession` → `.run(prompt)` / `.resultAs[O].run(input)`. One-shot: `run(prompt)`, `resultAs[O].{autonomous,interactive}.run(input)`. Ephemeral multi-turn: `chat(): Chat` → `.run(prompt)` / `.resultAs[O]...run(input)`. Tuning: `haiku`/`sonnet`/`opus`/`fable`, `cheap` (→ haiku), `withModel(Model)`, `withCheapModel`, `withConfig`, `withSystemPrompt`, `withName`, `withReadOnly`, `withNetworkOnly`, `withNetworkTools`, `withSelfManagedGit` | Claude Code coding/reviewing agent. Bare `claude` is **Opus with the 1M-token context window** (the long-lived implementer; reviewers share it); use `claude.sonnet`/`claude.haiku` for cheap one-shot calls, or `claude.fable` for the hardest ones. `interactive` mode lives only on `resultAs[O]`. See [Sessions](#sessions) for durable (`session`) vs ephemeral (`run`/`chat`). |
| `codex` | Durable: `session(name, seed): FlowSession` → `.run(prompt)` / `.resultAs[O].run(input)`. One-shot: `run(prompt)`, `resultAs[O].{autonomous,interactive}.run(input)`. Ephemeral multi-turn: `chat(): Chat` → `.run(prompt)` / `.resultAs[O]...run(input)`. Tuning: `mini`, `cheap` (→ mini), `withModel(Model)`, `withCheapModel`, `withConfig`, `withSystemPrompt`, `withName`, `withReadOnly`, `withNetworkOnly`, `withSelfManagedGit` | OpenAI Codex coding/reviewing agent. |
| `opencode` | Durable: `session(name, seed): FlowSession` → `.run(prompt)` / `.resultAs[O].run(input)`. One-shot: `run(prompt)`, `resultAs[O].{autonomous,interactive}.run(input)`. Ephemeral multi-turn: `chat(): Chat` → `.run(prompt)` / `.resultAs[O]...run(input)`. Tuning: `anthropicOpus`/`anthropicSonnet`/`anthropicHaiku`, `openaiSol`/`openaiTerra`/`openaiLuna`, `cheap` (provider-matched: openai→luna, else anthropicHaiku), `withCheapModel`, `withModel(providerModel)` / `withModel(provider, modelId)`, `withConfig`, `withSystemPrompt`, `withName`, `withReadOnly`, `withNetworkOnly`, `withSelfManagedGit` | [OpenCode](https://opencode.ai) coding/reviewing agent, driven over HTTP+SSE against a headless `opencode serve` (started lazily, shared for the run; sessions survive it — see [Sessions](#sessions)). Spans providers, so models are provider-qualified: use an accessor (`opencode.openaiLuna`) or `opencode.withModel("openai/gpt-5-mini")` / `opencode.withModel("ollama", "llama3.1")`. Inherits the user's configured `opencode` providers/auth. |
| `pi` | Durable: `session(name, seed): FlowSession` → `.run(prompt)` / `.resultAs[O].run(input)`. One-shot: `run(prompt)`, `resultAs[O].{autonomous,interactive}.run(input)`. Ephemeral multi-turn: `chat(): Chat` → `.run(prompt)` / `.resultAs[O]...run(input)`. Tuning: `withModel(Model)`, `withCheapModel`, `withConfig`, `withSystemPrompt`, `withName`, `withReadOnly`, `withNetworkOnly`, `withSelfManagedGit` | [Pi](https://pi.dev/) coding agent backend, driven through `pi --mode rpc`. Pi handles provider/model selection through its own CLI configuration; pin a model with `pi.withModel(Model("provider/model"))`. Interactive calls can ask clarifying questions via Orca's `ask_user` bridge. |
| `gemini` | Durable: `session(name, seed): FlowSession` → `.run(prompt)` / `.resultAs[O].run(input)`. One-shot: `run(prompt)`, `resultAs[O].{autonomous,interactive}.run(input)`. Ephemeral multi-turn: `chat(): Chat` → `.run(prompt)` / `.resultAs[O]...run(input)`. Tuning: `flash`, `cheap` (→ flash), `withModel(Model)`, `withCheapModel`, `withConfig`, `withSystemPrompt`, `withName`, `withReadOnly`, `withNetworkOnly`, `withSelfManagedGit` | Google Gemini CLI coding/reviewing agent, driven via `gemini --output-format stream-json`. Bare `gemini` pins **Gemini 2.5 Pro**; use `gemini.flash` for cheaper one-shot calls. Structured output is prompt-enforced (Gemini has no schema flag); `withReadOnly` maps to `--approval-mode plan`. See [ADR 0015](adr/0015-gemini-stream-json-driver.md). |
| `git` | `createBranch`, `checkout`, `ensureClean`, `commit`, `forceAdd`, `push`, `currentBranch`, `diff`, `diffVsBase`, `defaultBase`, `log`, `resetHard`, `deleteBranch`, `addWorktree`, `removeWorktree`, `listWorktrees`, `diffBranchExcludingOrca` | Git operations against the working tree. Recoverable failures (`BranchAlreadyExists`, `BranchNotFound`, `NothingToCommit`, `PushFailure` — `NonFastForward`/`RemoteDeclined` — `WorktreeAddFailed`, `WorktreeNotFound`) surface as `Either`; `.orThrow` converts a `Left` back to an exception when the case is unexpected. `forceAdd`, `resetHard`, `deleteBranch` are used by the flow runtime for bookkeeping and teardown. |
| `gh` | `createPr`, `updatePr`, `readIssue`, `readIssueComments`, `readPrComments`, `writeComment(pr, body)` / `writeComment(issue, body)`, `upsertComment(pr, marker, body)` / `upsertComment(issue, marker, body)`, `buildStatus`, `waitForBuild` | GitHub PR + CI integration via the `gh` CLI. `createPr` is idempotent by branch (returns the existing PR if one is open); `upsertComment` finds a prior comment carrying `marker` and edits it in place (safe on re-run — use `orcaCommentMarker(userPrompt, purpose)` to embed the prompt hash as the marker). `updatePr` replaces a PR's title + body. `waitForBuild` returns `Either[BuildWaitFailed, …]`. |
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
flow(OrcaArgs(args), _.pi):
  val session = pi.session("run", seed = userPrompt)
  stage("Run"):
    session.run(userPrompt)
```

## Coding agent tools

There are two ways to drive a model in a flow:

- **The leading agent — `agent`.** Backend-agnostic: it's whatever the `flow`
  selector picked (`_.claude`, `_.codex`, …). Use it for the flow's planning,
  implementation, reviewing, and its durable session (`agent.session(name,
  seed)` → a `FlowSession`, then `.run(...)` on it). Switch the selector and
  the whole flow follows; you never name a backend in the body.
- **A specific agent + model — `claude.opus`, `codex.mini`, `opencode.openaiLuna`.**
  Use a concrete accessor when you want a particular backend or tier, or for
  interactive planning (`Plan.interactive` needs a concrete backend). The tier
  accessors (`.opus`/`.sonnet`/…) live on the concrete agents, not on `agent` —
  so `agent.opus` won't compile; that's the cue to name the backend. Pin any
  other model with `withModel(Model("…"))`. Don't mix the two for one session
  (a `SessionId` is backend-typed).

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
granularity, so there `Only` widens to full auto-approve.
So for an unattended run the practical safety boundary is process isolation: run
the flow in a sandbox. We recommend [Sandcat](https://github.com/VirtusLab/sandcat),
[Docker Sandboxes](https://docs.docker.com/ai/sandboxes/), or any other sandboxing
solution.

## Flow methods

Top-level, available via `import orca.*`:

| Method | Signature | Use |
|---|---|---|
| `flow(args, agent, ...)(body)` | `flow(args: OrcaArgs, agent, branchNaming?, returnToStartBranch = false, progressStore?)(body)` | Entry point. Creates one feature branch + one progress log for the run. `agent` selects the leading coding agent — e.g. `_.claude` or `_.codex`. Branch naming defaults to a short cheap-model-generated label (slugged); pass `branchNaming = Some(BranchNamingStrategy.issue(handle))` to override (e.g. for issue flows). See [The flow lifecycle](#the-flow-lifecycle) for the full branch/teardown behavior. |
| `agent` (in-body accessor) | `agent: Agent[?]` | The leading agent resolved from the `flow` selector — see [Coding agent tools](#coding-agent-tools). |
| `stage[T: JsonData](name, commitMessage?)(body)` | `(name: String, commitMessage: Option[T => String] = None)(body): T` | The committing, resumable unit of work. On success, records the result, force-adds the progress log, and commits (code changes + log delta = one commit). On re-run, a stage whose result is still recorded is skipped and the stored value is returned. `T` must have `JsonData` — `case class Foo(...) derives JsonData` is enough. Commit message defaults to an `agent.cheap` summary of the diff; override via `commitMessage`. |
| `display(message)` | `(message: String): Unit` | Progress-only output: no stage, no commit, no log entry. Callable anywhere — outside a stage or inside a fork. |
| `Par.mapUnordered(n)(items)(f)` | `(parallelism: Int)(items: Seq[A])(f: A => R): List[R]` | The sanctioned script fan-out (no Ox import needed). Ephemeral agent turns (`agent.run`, `chat.run`) work inside `f`; the durable, flow-thread-only operations (`stage`, `agent.session`, `session.run`) throw if called from a fork. Results arrive in completion order. |
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
flow(OrcaArgs(args), _.claude, claude = Some(w => ClaudeAgents.default(w).opus))
// …or wrap a prebuilt agent:
flow(OrcaArgs(args), _.claude, claude = Some(_ => myAgent))
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
  read-only follow-ups from a fork (turns are not persisted; one live
  continuation at a time).

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
its full history. Note: opencode sessions are durable across a
process restart too — opencode persists them in its own global on-disk store,
independent of orca's per-run `opencode serve` process, so a freshly spawned
server resumes a prior run's session the same way codex/claude resume theirs.
As with every backend, that holds on the same machine with the backend's store
intact; otherwise the flow re-seeds safely (the uniform fallback).

`agent.cheap` returns the backend's cheap/fast variant (claude → haiku, codex →
mini, gemini → flash, opencode → anthropicHaiku, others → self) — used by the
runtime for branch naming and default commit messages.

## Authoring rules

Mutations outside a stage body are compile errors (see [Side effects happen
inside stages](#side-effects-happen-inside-stages)). The rules below are the
structural conventions you choose to follow as a flow author.

1. **Reads outside, mutations inside.** Only side-effecting work goes in a
   stage. Pure reads (`git.diff`, `gh.readIssue`, `fs.read`, `gh.waitForBuild`)
   run outside stages — staging them wastes commits and checkpoints.
   `agent.session(name, seed)` also runs outside stages, but it isn't a pure read
   — it records a session in the progress log.

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
| `lint(command, agent, instructions?)` | Run a shell lint and have `agent` summarise its output as a `ReviewResult`. Short output is inlined into the prompt; anything larger is written to a file under `.orca/` for the agent to read, so unbounded output can't overflow the context. |
| `reviewAndFixLoop(coderSession, reviewers, task, ..., fixInstructions?)` | Run reviewers against `task`, collect findings above the confidence threshold, hand them to the `coderSession` (a `FlowSession`) to fix, re-evaluate. Halts when reviewers come back clean, the fixer marks every remaining issue as won't-fix, or the iteration cap is reached. |
| `allReviewers(base)` | All eight canonical reviewer agents (code-functionality, test, readability, code-structure, simplicity, performance, security, scala-fp) layered on top of `base`. |
| `minimalReviewers(base)` | Universally-applicable subset (code-functionality, readability, test). Pair with the default LLM-driven selector when the full set is overkill. |
| `fixLoop(evaluate, fix, ...)` | Lower-level primitive `reviewAndFixLoop` is built on. |

`reviewAndFixLoop`'s `reviewerSelection` defaults to
`ReviewerSelector.agentDriven` — a picker LLM on the lead agent's cheap tier
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
| `summarisePr(agent, diff, context?, instructions?)` | Fold a branch diff into a `PrSummary(title, body)` for `gh.createPr`. `context` is an optional preamble (originating issue link, user prompt, etc.) the model anchors the description to. Use a cheap model (`claude.cheap`, `<lead>.cheap`). |

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
  exposed via `FlowSession.id` / `Chat.id`. Carries the backend identity at
  the type level, so you cannot accidentally pass a Claude session to Codex.
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

- **Launcher (zero config):** `flow(OrcaArgs(args), _.opencode, opencode =
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
