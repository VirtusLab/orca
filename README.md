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
//> using dep "org.virtuslab::orca:0.0.14"
//> using jvm 21

import orca.{*, given}

// The leading model is required: `_.claude` for Claude, `_.codex` for Codex.
flow(OrcaArgs(args), _.claude):
  // `stage` is the committing, resumable unit of work. The plan is produced in
  // one agentic turn and recorded in the stage log; a re-run with the same
  // prompt skips this stage and reads the stored Plan back.
  // plan.brief is always present — feed it to `llm.session(seed = plan.brief)`.
  val plan = stage("Plan"):
    Plan.autonomous.from(userPrompt, claude).value  // .value takes the Plan, discarding the planner's session

  // Get-or-create the implementer session (pure: id reserved, backend created
  // on first use). The seed (plan.brief) primes it on first use and is
  // replayed if the backend session is lost on resume.
  val session = claude.session(seed = plan.brief)

  // One stage per task: each stage commits its work + a progress-log entry as
  // one commit. Completed stages are skipped on resume — re-running the same
  // prompt picks up from the first incomplete task.
  for task <- plan.tasks do
    stage(s"task: ${task.title}"):      // skipped on resume if already done
      claude.runSeeded(task.description, session)
      reviewAndFixLoop(                  // runs under this stage
        coder = claude, sessionId = session,
        reviewers = allReviewers(claude),
        // claude.cheap picks the per-task reviewer subset; swap for
        // `ReviewerSelector.allEveryRound` to run every reviewer.
        reviewerSelection = ReviewerSelector.llmDriven(claude.cheap),
        task = task.title.value,
        // Format after every edit so commits stay formatted and reviewers
        // skip style nits.
        formatCommand = Some("cargo fmt"),
        // Cheap sanity gate; correctness is the reviewers' and CI's job.
        lintCommand = Some("cargo check --tests"),
        lintLlm = Some(claude.cheap)
      )
```

```bash
scala-cli run implement.sc -- "Add a rate-limiter to the /login endpoint"
```

The feature branch is auto-created from the prompt and auto-deleted if nothing
substantive landed (e.g. an early-exit flow). On failure the flow stays on the
feature branch so a re-run resumes in place — HEAD is already on the right
branch and the committed progress log is in the working tree.

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
| `claude` | `autonomous.run(prompt, session?)`, `resultAs[O].{autonomous,interactive}.run(input, session?)`, `newSession`, `session(seed)`, `runSeeded(prompt, session)`, `haiku`/`sonnet`/`opus`/`fable`, `cheap` (→ haiku), `withCheapModel`, `sessionExists(session)`, `withConfig`, `withSystemPrompt`, `withName`, `withReadOnly`, `withNetworkOnly`, `withNetworkTools`, `withSelfManagedGit` | Claude Code coding/reviewing agent. Bare `claude` is **Opus with the 1M-token context window** (the long-lived implementer; reviewers share it); use `claude.sonnet`/`claude.haiku` for cheap one-shot calls, or `claude.fable` for the hardest ones. `interactive` mode lives only on `resultAs[O]`. `session`/`runSeeded` are flow extensions (see [Sessions](#sessions)). |
| `codex` | `autonomous.run(prompt, session?)`, `resultAs[O].{autonomous,interactive}.run(input, session?)`, `newSession`, `session(seed)`, `runSeeded(prompt, session)`, `mini`, `cheap` (→ mini), `withCheapModel`, `sessionExists(session)`, `withConfig`, `withSystemPrompt`, `withName`, `withReadOnly`, `withNetworkOnly`, `withSelfManagedGit` | OpenAI Codex coding/reviewing agent. |
| `opencode` | `autonomous.run(prompt, session?)`, `resultAs[O].{autonomous,interactive}.run(input, session?)`, `newSession`, `session(seed)`, `runSeeded(prompt, session)`, `anthropicOpus`/`anthropicSonnet`/`anthropicHaiku`, `openaiGpt5`/`openaiGpt5Codex`/`openaiGpt5Mini`, `cheap` (→ anthropicHaiku), `withCheapModel`, `withModel(providerModel)` / `withModel(provider, modelId)`, `withConfig`, `withSystemPrompt`, `withName`, `withReadOnly`, `withNetworkOnly`, `withSelfManagedGit` | [OpenCode](https://opencode.ai) coding/reviewing agent, driven over HTTP+SSE against a headless `opencode serve` (started lazily, shared for the run). Spans providers, so models are provider-qualified: use an accessor (`opencode.openaiGpt5Mini`) or `opencode.withModel("openai/gpt-4o-mini")` / `opencode.withModel("ollama", "llama3.1")`. Inherits the user's configured `opencode` providers/auth. |
| `pi` | `autonomous.run(prompt, session?)`, `resultAs[O].{autonomous,interactive}.run(input, session?)`, `newSession`, `session(seed)`, `runSeeded(prompt, session)`, `withCheapModel`, `withConfig`, `withSystemPrompt`, `withName`, `withReadOnly`, `withNetworkOnly`, `withSelfManagedGit` | [Pi](https://pi.dev/) coding agent backend, driven through `pi --mode rpc`. Pi handles provider/model selection through its own CLI configuration; pin a model with `pi.withConfig(LlmConfig(model = Some(Model("provider/model"))))`. Interactive calls can ask clarifying questions via Orca's `ask_user` bridge. |
| `gemini` | `autonomous.run(prompt, session?)`, `resultAs[O].{autonomous,interactive}.run(input, session?)`, `newSession`, `session(seed)`, `runSeeded(prompt, session)`, `flash`, `cheap` (→ flash), `withCheapModel`, `withConfig`, `withSystemPrompt`, `withName`, `withReadOnly`, `withNetworkOnly`, `withSelfManagedGit` | Google Gemini CLI coding/reviewing agent, driven via `gemini --output-format stream-json`. Bare `gemini` pins **Gemini 2.5 Pro**; use `gemini.flash` for cheaper one-shot calls. Structured output is prompt-enforced (Gemini has no schema flag); `withReadOnly` maps to `--approval-mode plan`. See [ADR 0015](adr/0015-gemini-stream-json-driver.md). |
| `git` | `createBranch`, `checkout`, `checkoutOrCreate`, `ensureClean`, `commit`, `forceAdd`, `push`, `currentBranch`, `diff`, `diffVsBase`, `defaultBase`, `log`, `resetHard`, `deleteBranch`, `addWorktree`, `removeWorktree`, `listWorktrees`, `diffBranchExcludingOrca` | Git operations against the working tree. Recoverable failures (`BranchAlreadyExists`, `BranchNotFound`, `NothingToCommit`, `PushRejected`, `WorktreeAddFailed`, `WorktreeNotFound`) surface as `Either`; `.orThrow` converts a `Left` back to an exception when the case is unexpected. `forceAdd`, `resetHard`, `deleteBranch` are used by the flow runtime for bookkeeping and teardown. |
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
flow(OrcaArgs(args)):
  val session = pi.session(seed = userPrompt)
  stage("Run"):
    pi.runSeeded(userPrompt, session)
```

## Coding agent tools

> [!WARNING]
> **Coding agent tool usage is auto-approved by default** (`tools =
> ToolSet.Full`, `autoApprove = AutoApprove.All`): write-capable turns let the
> agent edit files and run shell commands without prompting. Constrain this in
> code, or isolate the whole run in a sandbox.

Two axes constrain an agent. **Capability** (`LlmConfig.tools: ToolSet`) is
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
// Restrict auto-approval to a named tool set (honoured by claude/codex).
val limited = claude.withConfig(
  LlmConfig(autoApprove = AutoApprove.Only(Set("Read", "Edit", "Grep")))
)
```

`AutoApprove.Only` fits interactive flows, where a human answers anything outside
the set; an autonomous turn has no one to approve, so an out-of-set call blocks
(and `gemini` has no per-tool granularity — `Only` widens to full auto-approve).
So for an unattended run the practical safety boundary is process isolation: run
the flow in a sandbox. We recommend [Sandcat](https://github.com/VirtusLab/sandcat),
[Docker Sandboxes](https://docs.docker.com/ai/sandboxes/), or any other sandboxing
solution.

## Flow methods

Top-level, available via `import orca.*`:

| Method | Signature | Use |
|---|---|---|
| `flow(args, leadModel, ...)(body)` | `flow(args: OrcaArgs, leadModel, branchNaming?, progressStore?)(body)` | Entry point. Creates one feature branch + one progress log for the run. `leadModel` selects the leading model — e.g. `_.claude` or `_.codex`. Branching defaults to a slug of the prompt; pass `branchNaming = Some(BranchNamingStrategy.issue(handle))` for issue flows. |
| `stage[T: JsonData](name, commitMessage?)(body)` | `(name: String, commitMessage: Option[T => String] = None)(body): T` | The committing, resumable unit of work. On success, records the result, force-adds the progress log, and commits (code changes + log delta = one commit). On re-run, a stage whose result is still recorded is skipped and the stored value is returned. `T` must have `JsonData` — `case class Foo(...) derives JsonData` is enough. Commit message defaults to an `llm.cheap` summary of the diff; override via `commitMessage`. |
| `display(message)` | `(message: String): Unit` | Progress-only output: no stage, no commit, no log entry. Callable anywhere — outside a stage or inside a fork. |
| `fail(message)` | `(message: String): Nothing` | Abort with a message. Triggers failure teardown: stays on the feature branch so a re-run resumes. |

### Side effects happen inside stages

Every side-effecting call must happen inside a `stage` body, and **the compiler
enforces it** — a mutation written outside a stage doesn't compile, so a flow
that side-effects without a checkpoint is a compile error, not a runtime
surprise. That covers git mutations (`commit`/`push`/`resetHard`/…), `fs.write`,
`gh` writes (`createPr`/`updatePr`/`writeComment`/`upsertComment`), and every
`llm.*.run`.

Reads (`git.diff`, `git.log`, `git.currentBranch`, `gh.readIssue`,
`gh.buildStatus`/`waitForBuild`, `fs.read`), `display`, and `fail` run anywhere.
`llm.session(seed)` also runs outside a stage — it records a session, not a side
effect (see [Sessions](#sessions)).

### The flow lifecycle

Each `flow(...)` run is bound to exactly one feature branch and one progress
log (`.orca/progress-<hash>.json`, where `<hash>` is derived from the prompt):

- **Start:** stash a dirty working tree with a warning (recover with `git stash
  pop`); create + checkout the feature branch; write and commit the progress log
  header.
- **Resume:** the progress log lives at a branch-independent, prompt-derived path,
  so recovery finds it before any checkout. Its header is validated as untrusted
  input (branch must match orca naming rules, prompt hash must match), then the run
  resumes from the first incomplete stage.
- **Success teardown:** remove the progress-log file in a final commit; delete
  the feature branch if it has no substantive changes vs the starting branch
  (throwaway-branch cleanup); return to the starting branch.
- **Failure teardown:** discard the failed stage's uncommitted partial edits with
  `git reset --hard`; stay on the feature branch so a re-run resumes in place.

### Sessions

`llm.session(seed)` is a get-or-create keyed by call-site position — the same
call site resumes the same session across re-runs. It reserves a `SessionId` and
records it in the progress log (no LLM call), and is callable outside a stage —
recording a session isn't a side effect.

```scala
val session = claude.session(seed = plan.brief)
claude.runSeeded(task.description, session)
```

The `seed` is the essential context to rebuild the agent — typically the **plan
brief**, or the issue body when there is no brief. `runSeeded` primes a fresh
session with the seed on first use; if the backend session is lost on resume it
re-seeds, prepending a progress preamble naming the completed stages; if the
session is still alive it continues it directly. (`newSession` gives a plain
fresh id with no get-or-create recording.)

`llm.cheap` returns the backend's cheap/fast variant (claude → haiku, codex →
mini, gemini → flash, opencode → anthropicHaiku, others → self) — used by the
runtime for branch naming and default commit messages.

## Authoring rules

Mutations outside a stage body are compile errors (see [Side effects happen
inside stages](#side-effects-happen-inside-stages)). The rules below are the
structural conventions you choose to follow as a flow author.

1. **Reads outside, mutations inside.** Only side-effecting work goes in a
   stage. Pure reads (`git.diff`, `gh.readIssue`, `fs.read`, `gh.waitForBuild`)
   run outside stages — staging them wastes commits and checkpoints.
   `llm.session(seed)` also runs outside stages, but it isn't a pure read — it
   records a session in the progress log.

2. **Push lives in a later stage than the edit that produced it.** A stage
   commits only on completion: a `git.push()` in the same stage as the edit would
   push nothing (the edit isn't committed yet). The push must be in a *separate,
   later* stage:

   ```scala
   stage("Write failing test"):
     claude.runSeeded("Write the failing test …", session)   // commits on completion

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

## Planning utilities

Available via `import orca.plan.*`:

The planning entry points form a **mode × operation grid**. The two axes are
orthogonal — every combination is valid. Mode is picked at the call site
(`Plan.autonomous.*` vs `Plan.interactive.*`), mirroring how `LlmTool` itself
splits `autonomous` / `interactive`:

| Operation | Result | `autonomous` (read-only + network, no human) | `interactive` (agent can `ask_user`) |
|---|---|---|---|
| `from(userPrompt, llm, instructions?)` | `Plan` | plan in one agentic turn | drive the planner conversationally |
| `assessThenPlan(userPrompt, llm, instructions?)` | `Verdict[Plan]` | assess, then `Proceed(plan)` or `Rejection(kind, body)` | same, but can ask the reporter to clarify instead of rejecting |
| `triage(report, llm, instructions?)` | `Triage` | classify a bug report (not-a-bug / untestable / testable) | same, with clarifying questions |

Every cell returns `Sessioned[B, <result>]` — the result paired with the agent
session that produced it. Continue that session into implementation
(`llm.runSeeded(task, session)` — the planning turn's session is still resumable
with write access), or `.value` it and get a fresh implementer session via
`llm.session(seed = plan.brief)`. Destructure positionally when you want both:
`val Sessioned(session, plan) = Plan.autonomous.from(...)`.

From a `Sessioned[B, Plan]`, an optional `.reviewed(llm)` step refines the plan
before implementing — the planner critiques its own draft, producing an improved
`Plan`. Chain it: `Plan.autonomous.from(...).reviewed(claude).value`.

`assessThenPlan` returns a `Verdict`: `Verdict.Proceed(plan)` to implement, or
`Verdict.Rejection(kind, body)` — a follow-up question, critique, or rebuff the
caller surfaces back to the reporter. `triage` returns a `Triage` sum type the
caller pattern-matches (`NotABug` / `Untestable` / `Testable`).

Review utilities, available via `import orca.review.*`:

| Method | Use |
|---|---|
| `lint(command, llm, instructions?)` | Run a shell lint, write its combined output to a temp file, and have `llm` read and summarise it as a `ReviewResult` (file, not prompt, so unbounded output can't overflow the context). |
| `reviewAndFixLoop(coder, sessionId, reviewers, task, ..., fixInstructions?)` | Run reviewers against `task`, collect findings above the confidence threshold, hand them to `coder` to fix, re-evaluate. Halts when reviewers come back clean, the fixer marks every remaining issue as won't-fix, or the iteration cap is reached. |
| `allReviewers(base)` | All eight canonical reviewer agents (code-functionality, test, readability, code-structure, simplicity, performance, security, scala-fp) layered on top of `base`. |
| `minimalReviewers(base)` | Universally-applicable subset (code-functionality, readability, test). Pair with the default LLM-driven selector when the full set is overkill. |
| `fixLoop(evaluate, fix, ...)` | Lower-level primitive `reviewAndFixLoop` is built on. |

`reviewAndFixLoop` requires a `reviewerSelection: ReviewerSelector` argument.
Typically `ReviewerSelector.llmDriven(claude.cheap)` — the picker LLM (use a
cheap model) sees each reviewer's description plus the changed file paths and
narrows the supplied list per task. Pass
`ReviewerSelector.allEveryRound` to run every reviewer every iteration, or
`ReviewerSelector.onlyPreviouslyReporting` to re-run only the reviewers that
found something last round.

PR utilities, available via `import orca.pr.*`:

| Method | Use |
|---|---|
| `summarisePr(llm, diff, context?, instructions?)` | Fold a branch diff into a `PrSummary(title, body)` for `gh.createPr`. `context` is an optional preamble (originating issue link, user prompt, etc.) the model anchors the description to. Use a cheap model (`claude.cheap`, `<lead>.cheap`). |

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
  agent generates in one round-trip. `epicId` is a kebab-case id used as the
  plan's git branch; `description` is the planner's epic summary; `brief` is a
  concise codebase briefing always included (feed it to `llm.session(seed =
  plan.brief)`). `taskPrompt(task)` prepends the brief to a task's description.
- **`orca.plan.Task(title, description, completed?)`** — `title` is the
  human-readable label shown in the event log.
- **`orca.plan.Sessioned(sessionId, value)`** — every `Plan.{autonomous,
  interactive}.*` operation returns one: the result paired with the agent
  session that produced it, so the caller can continue that session into
  implementation or `.value` it and start fresh.
- **`orca.plan.Verdict[A]`** — `Verdict.Proceed(value)` or
  `Verdict.Rejection(kind, body)` (kind ∈ Question / Critique / Rebuff).
  Returned by `assessThenPlan` as `Verdict[Plan]`.
- **`orca.plan.Triage`** — sum type returned by `triage`: `NotABug`,
  `Untestable`, or `Testable` — each carrying exactly the fields its branch
  needs.
- **`orca.plan.BugReportMatch`** — the agent's decision on whether a CI failure
  matches the original report.
- **`orca.llm.SessionId[B]`** — typed session id, parameterised by backend.
  Returned by `llm.session(seed)` and passed to `llm.runSeeded`. Carries the
  backend identity at the type level, so you cannot accidentally pass a Claude
  session to Codex.
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

- **Launcher (zero config):** `flow(OrcaArgs(args), opencodeLauncher =
  OpencodeLauncher.ollama("qwen3-coder"))`. Orca starts the server via `ollama
  launch opencode`, which injects Ollama's provider config and pins that one
  model — use bare `opencode`, no `withModel`. Needs the `ollama` CLI and the
  model pulled.
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

- [`design.md`](design.md) — architecture and design rationale (describes the
  pre-0018 flow model; see [ADR 0018](adr/0018-stage-bound-flow-runtime.md) for
  the current stage/session runtime design).
- [`adr/`](adr/) — architecture decision records.
- [`AGENTS.md`](AGENTS.md) — internals, conventions, build/test recipes; the
  same file AI assistants pick up.

## License

Apache 2.0 — see [LICENSE](LICENSE).

## Copyright

Copyright (C) 2026 VirtusLab [https://virtuslab.com](https://virtuslab.com).
