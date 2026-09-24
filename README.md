# Orca

Deterministic, AI-driven development flows.

Orca allows you to programmatically define software development workflows where
AI agents perform the coding. If you want AI-generated code to always be
reviewed by another agent, don't try to coerce the agents; just express that
requirement in code. Don't waste tokens on formatting, committing, or creating
PRs - all of this can be handled by an ordinary script.

Orca comes with an `orca` cli, which can be used interactively by humans, or
headlessly by humans and agents alike. A number of built-in flows, implementing
e.g. a plan-implement-review loop, allow you to start using Orca right away.

Orca flow scripts are written in Scala, and can be run with a single command
through [scala-cli](https://scala-cli.virtuslab.org), which is installed by the
`orca` installer. No other dependencies are needed - everything is automatically
bootstrapped. Scala 3 looks like Python, but with types - so you get quick
feedback if your flow script has any problems.

Orca's development flows are resumable, so that if work is interrupted mid-flow
for any reason, it can be continued from the last commit. 

You can use Orca to orchestrate development in any language and ecosystem.

Orca assumes that it has configured, logged-in access to Claude, Codex,
OpenCode, or Pi (depending which backend you use), as well as `gh` and `git`.

Install with one command, which installs `scala-cli` (via its official
installer) if you don't have it already, and writes the `orca` executable to
`~/.local/bin/orca`:

```bash
curl -fsSL https://raw.githubusercontent.com/VirtusLab/orca/master/install.sh | bash
```

See [Orca Shell](#orca-shell) for the details and the full command-line
reference, or just run `orca` / `orca help`.

## Three ways to work with Orca

**Interactively**: install the CLI, run `orca`, pick a flow (`implement.sc`
comes first in the list) and enter your task. Non-interactively, use `orca run
<flow> "<task>"`. See [Orca Shell](#orca-shell) for installation and the full
command-line reference.

> [!WARNING] **Orca is designed to work in a sandboxed environment!** Coding
> agent tool usage is auto-approved by default (`tools = ToolSet.Full`,
> `autoApprove = AutoApprove.All`): write-capable turns let the agent edit files
> and run shell commands without prompting. This can be changed by changing the
> flow's options in code. Alternatively, use a VPS or local sandbox such as
> [Sandcat](https://github.com/VirtusLab/sandcat), [Docker
> Sandboxes](https://docs.docker.com/ai/sandboxes/), or any other.

**Driven by an agent (headless)**: a coding agent or harness invokes the CLI
non-interactively to implement a task, e.g. from CI or as a sub-task of another
agent:

```bash
orca run implement.sc "add a rate limiter to /login"
```

Useful flags: `--branch <name>` (name the branch the run creates),
`--skip-branch` (continue on the current branch instead of creating one),
`--keep-changes` (leave uncommitted files in place instead of
stashing them) and `--worktree` (run in a git worktree of this repository
instead of the current checkout).

In every mode, which agent (and model) handles the planning, coding, and review
roles comes from `settings.properties` — written for you by the shell's
first-run wizard or `orca config`, hand-editable too; see [Settings](#settings).

Agents can load [`skills/orca`](skills/orca/SKILL.md) to know when
and how to delegate here; in Claude Code, `/orca [task]` asks which flow to run
and where, then starts it — installable as a Claude Code plugin, a Pi package, or
by symlinking into any harness's skills directory; see [its
README](skills/orca/README.md) for specifics.

**As a script**: run a flow directly with `scala-cli`, no install required — see
[An example flow](#an-example-flow).

```bash
scala-cli run --workspace "$(mktemp -d)" implement.sc -- "add a rate limiter to /login"
```

## An example flow

Save this as `implement.sc` and run it with your task:

```scala
//> using scala 3.9.0
//> using dep "org.virtuslab::orca:0.1.9"
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
    Plan.autonomous.from(userPrompt, planningAgent).value  

  // One stage per task: each stage commits its work + a progress-log entry as
  // one commit. Completed stages are skipped on resume — re-running the same
  // prompt picks up from the first incomplete task. A session is keyed by the
  // stage that mints it, so each task gets its own, seeded with the plan's
  // brief (which primes it on first use, and is replayed if the backend session
  // is lost on resume).
  for task <- plan.tasks do
    stage(s"Task: ${task.title}"):
      val session = codingAgent.session("implementer", seed = plan.brief)
      session.run(task.description)
      reviewThenFix(
        coderSession = session,
        reviewers = allReviewers(reviewAgent),
        // One review round, one fix turn. Reviewers are picked by a picker LLM
        // on reviewAgent.cheap (see "Review utilities"); format and lint
        // default to the project's stack settings
        // (`.orca/settings.properties`, auto-discovered on first run) — see
        // "Settings" below. The whole task goes in: reviewers are shown its
        // title and description, plus the run's prompt, each labelled.
        task = task
      )

  // Each task's single pass took the fixer's word for its own fixes; this loop
  // over everything the run changed is what checks them.
  val openFindings = stage("Final review"):
    reviewAndFixLoop(
      coderSession = session,
      reviewers = allReviewers(reviewAgent),
      task = Task(Title("The whole planned change"), plan.brief),
      diff = ReviewDiff.WholeRun,
      maxFixTurns = 5
    )

  // Best effort: opens a PR when the checkout is on a GitHub `gh` can reach,
  // and says why in one line when it isn't. What the loop left open is listed
  // in the PR body.
  openPrIfGitHub(
    summarisingAgent = codingAgent.cheap,
    openFindings = openFindings
  )
```

```bash
scala-cli run --workspace "$(mktemp -d)" implement.sc -- "Add a rate-limiter to the /login endpoint"
```

Each flow starts by creating a feature branch, named by a short
cheap-model-generated label derived from the prompt (slugged; pass `branchNaming
= ...` to override, or `--branch <name>` to name it yourself). On success the flow opens a PR when the repository is on a
GitHub `gh` can reach, and hands you back the branch you started on — the work
is on the PR. Otherwise it says so in one line and leaves you on the feature
branch, ready to test or open a PR by hand — see [The flow
lifecycle](#the-flow-lifecycle) for the full success/failure/resume behavior.

If the flow is interrupted — user intervention or an intermittent error — just
run the same command again: it resumes from the last committed set of changes,
so only a small amount of work is repeated. Orca borrows ideas from durable
computing: which stages have completed, and with what results, is tracked in a
progress file committed alongside the modified code, making commits the unit of
atomicity — the progress log can't drift from the changes in the repository.
When the flow is done, the progress log is removed from the branch in one last
commit, which is pushed too if the flow had already pushed the branch.

There are two runnable examples under
[`examples/runnable/`](examples/runnable/):
* [01-simple](examples/runnable/01-simple/) (in-memory plan + review, autonomous
  planner),
* [02-interactive](examples/runnable/02-interactive/) (same shape as 01, but the
  planner can ask clarifying questions via `ask_user`).

More flow scripts — `issue-pr.sc`, `issue-pr-bugfix.sc`,
`implement-enhanced.sc`, `review.sc` — live in [`flows/`](flows/); run them
against your own git repo.

For convenient editing of Orca flow scripts, with code-completion, you can try
the [Metals](https://scalameta.org/metals/) VSCode extension.

## Built-in tools

The following are available inside a `flow(...) { ... }`.

The five coding agents — `claude`, `codex`, `opencode`, `pi`, `gemini` — share
one call surface. Durable: `session(name, seed): FlowSession` →
`.run(prompt)` / `.resultAs[O].run(input)`. One-shot: `run(prompt)`,
`resultAs[O].{autonomous,interactive}.run(input)`. Ephemeral multi-turn:
`chat(): Chat` → `.run(prompt)` / `.resultAs[O]...run(input)`. Common tuning:
`withModel`, `withCheapModel`, `withAutoApprove`, `withSystemPrompt`, `withName`,
`withReadOnly`, `withNetworkOnly`, `withSelfManagedGit`. The table lists each
backend's model accessors and backend-specific extras:

| Tool | Backend-specific methods | Purpose |
|---|---|---|
| `claude` | `haiku`/`sonnet`/`opus`/`fable`, `cheap` (→ haiku), `withModel(Model)`, `withNetworkTools` | Claude Code coding/reviewing agent. Bare `claude` is **Opus with the 1M-token context window** (the coder; reviewers share it); use `claude.sonnet`/`claude.haiku` for cheap one-shot calls, or `claude.fable` for the hardest ones. `interactive` mode lives only on `resultAs[O]`. See [Sessions](#sessions) for durable (`session`) vs ephemeral (`run`/`chat`). |
| `codex` | `mini`, `cheap` (→ mini), `withModel(Model)` | OpenAI Codex coding/reviewing agent. Bare `codex` pins **GPT-6 Sol** (needs a codex CLI that offers it); use `codex.mini` (GPT-6 Luna) for cheap one-shot calls. |
| `opencode` | `anthropicOpus`/`anthropicSonnet`/`anthropicHaiku`, `openaiAstra`/`openaiSol`/`openaiLuna`, `cheap` (provider-matched: openai→luna, else anthropicHaiku), `withModel(providerModel)` / `withModel(provider, modelId)` | [OpenCode](https://opencode.ai) coding/reviewing agent, driven over HTTP+SSE against a headless `opencode serve` (started lazily, shared for the run; sessions survive it — see [Sessions](#sessions)). Spans providers, so models are provider-qualified: use an accessor (`opencode.openaiLuna`) or `opencode.withModel("openai/gpt-5-mini")` / `opencode.withModel("ollama", "llama3.1")`. Inherits the user's configured `opencode` providers/auth. |
| `pi` | `withModel(Model)` | [Pi](https://pi.dev/) coding agent backend, driven through `pi --mode rpc`. Pi handles provider/model selection through its own CLI configuration; pin a model with `pi.withModel(Model("provider/model"))`. Interactive calls can ask clarifying questions via Orca's `ask_user` bridge. |
| `gemini` | `flash`, `cheap` (→ flash), `withModel(Model)` | Google Gemini CLI coding/reviewing agent, driven via `gemini --output-format stream-json`. Bare `gemini` pins **Gemini 3.1 Pro (preview)**; use `gemini.flash` (Gemini 3.8 Flash) for cheaper one-shot calls. Structured output is prompt-enforced (Gemini has no schema flag); `withReadOnly` maps to `--approval-mode plan`. See [ADR 0015](adr/0015-gemini-stream-json-driver.md). |
| `git` | `push`, `head`, `headCommit`, `isAncestorOfHead`, `uncommittedDiff`, `changedFiles`, `reviewChanges`, `pendingChanges`, `diffVsBase`, `defaultBase`, `show`, `fileAt` | Git reads against the working tree, plus `push`. The runtime owns the run's branch and commits, so branch switching and committing are not on `git`. Commits are typed (`orca.gitref.CommitHash`); `head` answers the branch HEAD is on or the commit it is detached at (`orca.gitref.Head`). Recoverable failures (`NoDefaultBase`, `PushFailure` — `NonFastForward`/`RemoteDeclined`, `GitReadFailed`) surface as `Either`; `.orThrow` converts a `Left` back to an exception when the case is unexpected. `uncommittedDiff` covers the whole repository minus `.orca/` bookkeeping, tracked files only, and is empty once the work is committed — `diffVsBase` is the branch-wide view. `reviewChanges` is what `reviewAndFixLoop` hands reviewers: that diff plus the contents of files new to the repo, together with the list of every path in the change set, how much of each changed, and each file's own part of the diff. It takes an optional commit to compare against (`headCommit` reads one) so work already committed still shows up. `changedFiles` is the path list on its own, for a consumer gating on file names — the diff text alone names neither a binary change nor a rename, and leaves a trailing tab on a path containing a space. `pendingChanges` describes what the next commit will include: a `--stat` summary, the new files, and the diff. |
| `gh` | `availability`, `createPr`, `updatePr`, `readIssue`, `readIssueComments`, `readPrComments`, `writeComment(pr, body)` / `writeComment(issue, body)`, `upsertComment(pr, marker, body)` / `upsertComment(issue, marker, body)`, `buildStatus`, `waitForBuild` | GitHub PR + CI integration via the `gh` CLI. `availability` is a read-only probe of whether a PR can be opened from this checkout, answering with a [`GitHubAvailability`](#data-structures). `createPr` is idempotent by branch (returns the existing PR if one is open); `upsertComment` finds a prior comment carrying `marker` and edits it in place (see [Authoring rules](#authoring-rules) for the re-run pattern). `updatePr` replaces a PR's title + body. `waitForBuild` returns `Either[BuildWaitFailed, …]`. |
| `fs` | `read`, `write`, `list` | Working-tree file I/O. `read` returns `Option[String]` so a missing file is a branch point, not an exception. `write` refuses a path outside the working tree or under `.orca/runs`, `.orca/cache` or `.orca/worktrees`. |

The runtime owns git: every write-capable agent turn is told not to commit,
push, or switch branches — it edits the working tree; the runtime commits each
stage and owns the run's branch, and the flow pushes via `git.push`. Opt out per-tool with
`claude.withSelfManagedGit`.

For the LLM interfaces, `resultAs[O]` defines the shape of the structured
output. The `O` type needs a `JsonData[O]` (provided by `derives JsonData` on a
case class) for schema generation and deserialization. A parameterless enum that
`derives JsonData` travels as its case name, and the schema lists every name; a
sum type whose cases carry fields cannot be an `O`. Additionally, you might
define an `Announce[O]` so that a friendly summary is printed in the event log,
instead of a raw json.

A minimal Pi-backed flow looks the same; Pi reads your normal Pi configuration:

```scala
flow(OrcaArgs(args)):
  stage("Run"):
    val session = pi.session("run", seed = userPrompt)
    session.run(userPrompt)
```

## Coding agent tools

There are two ways to drive a model in a flow:

- **The role agents — `planningAgent`/`codingAgent`/`reviewAgent`.**
  Backend-agnostic: each is resolved from settings (see [Settings](#settings)),
  defaulting to claude. Use `planningAgent` for `Plan.*` calls, `codingAgent`
  for the implementer's durable session, and `reviewAgent` for
  `allReviewers(...)` and the review machinery's defaults. Edit settings and the
  whole flow follows; you never name a backend in the body.
- **A specific agent + model — `claude.opus`, `codex.mini`,
  `opencode.openaiLuna`.** Use a concrete accessor when you want a particular
  backend or tier regardless of settings — say `claude.opus` for a step that
  must have the strongest model even where the coding role is a cheaper backend.
  None of the shipped flows do this; they all follow the roles. The tier
  accessors (`.opus`/`.sonnet`/…) live on the concrete agents, not on the role
  accessors — so `codingAgent.opus` won't compile; that's the cue to name the
  backend. Pin any other model with `withModel(Model("…"))`.

Two axes constrain an agent. **Capability** (`ToolSet`) is
which tools exist at all:

```scala
// ReadOnly — reads only, no shell, no edits (reviewers, plan review, brief).
val reviewer = claude.withReadOnly

// NetworkOnly — reads plus read-only network (web, and on claude a host-served
// GitHub issue/PR read), for planners that must read an issue/PR. How strongly
// each backend blocks edits varies — see the enforcement matrix in AGENTS.md.
val planner = claude.withNetworkOnly

// Full (the default) — write-capable.
```

**Prompting** (`autoApprove`) is which of the available tools auto-approve
without a y/n prompt — only meaningful for interactive turns, and consulted only
on `Full`:

```scala
// Restrict auto-approval to a named tool set (honoured by claude).
val limited =
  claude.withAutoApprove(AutoApprove.Only(Set("Read", "Edit", "Grep")))
```

`AutoApprove.Only` fits interactive flows, where a human answers anything
outside the set; an autonomous turn has no one to approve, so an out-of-set call
blocks. Only claude enforces the set per tool — codex and gemini have no
per-tool granularity, so there `Only` widens to full auto-approve. For an
unattended run the practical boundary is a sandbox:
[Sandcat](https://github.com/VirtusLab/sandcat), [Docker
Sandboxes](https://docs.docker.com/ai/sandboxes/), or any other.

## Your own agent setup

Orca's agents are ordinary harness sessions — `claude`, `gemini`, `codex`,
`opencode` or `pi` — started in your repository. They load the same instruction
files (`~/.claude/CLAUDE.md`, `CLAUDE.md`, `CLAUDE.local.md`, `AGENTS.md`,
`GEMINI.md`, …), MCP servers, plugins and hooks as your own sessions.

Autonomous turns have no one to answer prompts (see above), so:

- Coding turns auto-approve everything by default (`withAutoApprove`
  narrows it).
- On claude, read-only roles (planner, reviewers, reviewer picker) deny any
  tool outside orca's list, your MCP tools included, unless your claude
  settings `permissions.allow` them.
- On claude, opencode and pi, cheap one-shots (branch names, default commit
  messages) run with no tools and no MCP servers.

Check your instructions for:

- **Mandatory tool calls.** "Always call X first" needs X allowed (see above),
  or write "if available".
- **A human in the loop.** In autonomous flows, "ask me before X" or "wait for
  confirmation" cannot work: no one is there to answer.

## Flow methods

Top-level, available via `import orca.*`:

| Method | Signature | Use |
|---|---|---|
| `flow(args, ...)(body)` | `flow(args: OrcaArgs, branchNaming?, stackSettings?, planningAgent?, codingAgent?, reviewAgent?)(body)` | Entry point. Creates one feature branch + one progress log for the run. The three role agents (below) resolve from settings — see [Settings](#settings) — defaulting to claude; `planningAgent`/`codingAgent`/`reviewAgent` here are per-role programmatic overrides (`Some(_.claude.opus)`) that win over both settings files. Branch naming defaults to a short cheap-model-generated label (slugged); pass `branchNaming = Some(BranchNamingStrategy.issue(handle))` to override (e.g. for issue flows). `stackSettings = Some(StackSettings(...))` pins the run's [stack settings](#settings) — the settings file's stack portion is then neither read nor written (the escape hatch for a language-specific flow; its agent keys are still honoured). See [The flow lifecycle](#the-flow-lifecycle) for the full branch/teardown behavior. |
| `planningAgent` (in-body accessor) | `planningAgent: Agent[?]` | The planning-role agent, resolved from settings — see [Coding agent tools](#coding-agent-tools). Hand it to `Plan.*`. |
| `codingAgent` (in-body accessor) | `codingAgent: Agent[?]` | The coding-role agent — the run's primary: implementer sessions, branch naming, stack discovery, default commit messages. |
| `reviewAgent` (in-body accessor) | `reviewAgent: Agent[?]` | The review-role agent: `allReviewers(reviewAgent)`, the reviewer-picker and the lint summariser default to its tiers. |
| `stage[T: JsonData](name, commitMessage?)(body)` | `(name: String, commitMessage: Option[T => String] = None)(body): T` | The committing, resumable unit of work. On success, records the result, force-adds the progress log, and commits (code changes + log delta = one commit). On re-run, a stage whose result is still recorded is skipped and the stored value is returned. `T` must have `JsonData` — `case class Foo(...) derives JsonData` is enough. Commit message defaults to a `codingAgent.cheap` summary of the diff; override via `commitMessage`. |
| `display(message)` | `(message: String): Unit` | Progress-only output: no stage, no commit, no log entry. Callable anywhere — outside a stage or inside a fork. |
| `Par.mapUnordered(n)(items)(f)` | `(parallelism: Int)(items: Seq[A])(f: A => R): List[R]` | The sanctioned script fan-out (no Ox import needed). Ephemeral agent turns (`codingAgent.run`, `chat.run`) work inside `f`; the durable, flow-thread-only operations (`stage`, `codingAgent.session`, `session.run`) throw if called from a fork. Results arrive in completion order. |
| `fail(message)` | `(message: String): Nothing` | Abort with a message. Triggers failure teardown: stays on the feature branch so a re-run resumes. |

### Overriding tools and agents

Any tool or agent `flow(...)` builds by default can be replaced by a named
argument. Plain tools take the value directly (`git = Some(myGit)`, `interaction
= Some(myInteraction)` — your own `orca.backend.Interaction` implementation,
e.g. for Slack; not exported from `orca.*`, so import it by its full path).
A `git` override is an `orca.tools.RuntimeGit`, since the runtime drives it too.
Agents take a **factory** that receives the run's `AgentWiring` (event sink,
interaction, workDir, prompts), so a tuned agent lands on the same dispatcher
as the defaults:

```scala
flow(OrcaArgs(args), claude = Some(w => ClaudeAgents.default(w).opus))
```

Factories exist for all five backends: `ClaudeAgents.default(w)`,
`CodexAgents.default(w)`, `GeminiAgents.default(w)`, `PiAgents.default(w)`, and
`OpencodeAgents.default(w, launcher)` — opencode's factory is applied where the
run's `Ox` scope exists (it pins a shared `opencode serve` to the scope), so its
slot is typed `AgentWiring => Ox ?=> OpencodeAgent`.

### Side effects happen inside stages

Every side-effecting call — `git.push`,
`fs.write`, `gh` writes, every `agent.*.run` — must happen inside a `stage`
body, and **the compiler enforces it**: a mutation outside a stage doesn't
compile. Pure reads (`git.uncommittedDiff`, `git.changedFiles`, `gh.readIssue`,
`gh.availability`, `fs.read`),
`display`, and `fail` run anywhere; `agent.session(name, seed)` runs inside or
outside a stage — it records a session, not a side effect. Where to
*place* effects is covered by the [Authoring rules](#authoring-rules).

### The flow lifecycle

Two words this section leans on: a **run** is one task's flow execution, across
however many processes it takes to finish it; an **attempt** is one of those
processes — one `orca run`, or one `flow(...)` call. An interrupted run is
resumed by attempting it again with the same task.

Each run is bound to exactly one feature branch and one progress log
(`.orca/runs/<key>.progress.json`, where `<key>` is derived from the task):

- **Start:** stash a dirty working tree with a warning (recover with `git stash
  pop`); create + checkout the feature branch; write and commit the progress log
  header. `--branch <name>` (`OrcaArgs.branch`) names that branch, winning
  over `branchNaming`; a protected or already existing name is refused rather
  than renamed. The three flags below reach a flow as one `OrcaArgs.target`
  (`RunTarget`), which has no case for a combination orca refuses. A script can
  also set that field itself — `flow(OrcaArgs(args).copy(target =
  RunTarget.Worktree))` — which overrides whatever the flags said.
  `--skip-branch` (`RunTarget.CurrentBranch`) binds the run to the CURRENT
  branch instead of creating one — for continuing work already planned on a
  branch — refusing on a protected branch or detached HEAD. On a FRESH
  `--skip-branch` run a dirty tree is tolerated, not stashed: uncommitted or
  untracked files (e.g. plan files left by a planning harness) stay in place for
  the flow, and get swept into the first stage's commit. `--keep-changes`
  (`Uncommitted.Keep` on either branch case) does the same on a FRESH run in
  either branch mode — in normal mode the files survive branch creation and
  reach the new branch in that first stage commit. With neither flag, a dirty
  tree on a fresh run is put to the user: stash (the default), keep, or abort;
  with no terminal to ask, it stashes. A run that already has a progress log —
  a resume, or one too broken to read — always stashes and ignores
  `--keep-changes`, so an interrupted stage's partial work can't leak into the
  stage that re-runs.
  `--worktree` (`RunTarget.Worktree`) runs the whole flow in
  `.orca/worktrees/<hash>` of this repository — a second checkout, keyed on the
  same prompt hash as the progress log, created on the first run and reused by
  every later one for that task. It isolates the run: two tasks can run at once
  without sharing a checkout or a branch. Uncommitted work does NOT come along —
  a worktree is made from a commit — so `--worktree` is refused with
  `--skip-branch` and with `--keep-changes`: `RunTarget.Worktree` carries
  neither a branch mode nor an `Uncommitted`, so the pair is refused while argv
  is parsed and has no representation after that. The first run in a worktree
  pays a cold build (no build outputs, no dependencies, none of the untracked
  local config a project may need), an editor or indexer that ignores
  `.gitignore` will see the second checkout, and orca never removes it. The run
  starts on an `orca-worktree-<hash>` branch orca also never deletes, so full
  cleanup is `git worktree remove .orca/worktrees/<hash>` **and** `git branch -d
  orca-worktree-<hash>`; a re-run of the task refuses rather than moving that
  branch if it has gained commits since.
- **Resume:** a re-run with the same prompt finds the progress log and resumes
  from the first incomplete stage (a `--branch` naming a different branch than
  the log's is refused). It says once which branch it bound, how many
  stages are already recorded, and that the interrupted stage's uncommitted work
  was not carried over; every durable session it re-enters through `session.run`
  is told the same — a re-seeded one in its preamble, a still-live one once, on
  its first turn (a conversation continued through `session.chat` is not
  told). A corrupt or truncated progress log is detected at startup — orca
  warns and starts fresh (previous stages re-run) rather than silently
  mis-resuming.
- **Success teardown:** remove the progress-log file in a final commit, and push
  it when the remote branch still carries the log (i.e. the flow pushed). A
  throwaway feature branch (no substantive changes vs the starting branch) is
  deleted and HEAD returns to the starting branch. Otherwise the feature branch
  is kept, and where HEAD lands follows the run: a run that created a branch and
  **opened a PR** hands you back the branch you started on (the work is on the
  PR). Every other run leaves you where you were — on the feature branch when no
  PR was opened or under `--skip-branch`, and untouched under `--worktree`,
  where the work is in the separate checkout the summary names. The run then
  closes by naming the branch you are left on, the PR it opened if it opened
  one, how many files changed since the commit it started from, and the `git
  diff` that shows them.
- **Failure teardown:** while HEAD is on the feature branch, discard the
  failed stage's uncommitted partial edits —
  `git reset --hard` for tracked files, plus `git clean -fd` for the files it
  newly created; stay on the feature branch so a re-run resumes in place.
  Gitignored paths and `.orca/` are never removed. Whether the clean runs at
  all is decided once, at setup, for the whole run: a FRESH run that kept a
  dirty tree instead of stashing it (`--skip-branch`, `--keep-changes`, or the
  interactive keep answer) leaves orca unable to tell those files apart from the
  run's own — no untracked file is deleted, in any stage, including ones the
  failed stage created. Kept edits to tracked files that no stage has
  committed yet are put back after the reset; a re-run stashes them before it
  resumes. When the failed body left HEAD elsewhere, teardown touches nothing
  and says so.

### Settings

Two files, both plain `key = value` lines, parsed once per run before setup:

- **`{workDir}/.orca/settings.properties`** — committed, hand-editable project
  settings: the stack commands (`format`/`lint`/`test`) and, per role, which
  agent to use.
- **`$XDG_CONFIG_HOME/orca/settings.properties`**, defaulting to
  `~/.config/orca/settings.properties` (also on macOS) — a per-user default,
  agent keys only. An absent global file is simply skipped.

Precedence, code always winning over files:

- **Roles:** `flow(planningAgent = ...)` (and `codingAgent`/`reviewAgent`)
  programmatic override > project file > global file > built-in default (claude,
  no model pin).
- **Stack commands:** `reviewAndFixLoop(formatCommands = Use(...)/Off)` >
  `flow(stackSettings = Some(...))` > project file > auto-discovery (which
  writes the file).

An unreadable or malformed file — project or global — aborts the run before any
tree mutation; the global file may contain ONLY agent keys, so a stack key there
is also an error.

**Stack commands.** Keys `format`, `lint`, and `test`. Each value is one shell
command, run via `bash -c` in the flow's working directory; everything after the
first `=` is command text (`lint = FOO=bar cargo check` works). Repeating a key
appends — the task's commands run in file order, so a multi-stack repo lists one
line per stack half. A key's value may also be the literal `off`, which
explicitly disables that task; a missing key has the same runtime effect (the
gate is skipped) but, unlike `off`, does not count as "configured" — see
Auto-discovery below. `#` lines are comments; commenting out a line is the same
as deleting it. A typical discovered project file:

```properties
# orca settings — edit freely, commit with the project.
# format/lint/test: one shell command per key; `off` disables the gate. Delete the stack lines (or the whole file) to re-run auto-discovery.
# planningAgent/codingAgent/reviewAgent (harness[:model]): override the global settings file; a flow's own code overrides both.
# Cargo.toml; via rustfmt
format = cargo fmt
# Cargo.toml
lint = cargo check --tests
# no test config found
test = off
```

**Agent keys.** `planningAgent`, `codingAgent`, and `reviewAgent`, valid in both
files, single-valued (a repeated agent key is an error). Value grammar:
`harness[:model]`, split at the first `:` so a model id containing `:` survives;
`harness` is one of `claude`, `codex`, `opencode`, `pi`, `gemini` (an
unrecognised name is an error naming the valid set). The model part is passed
**verbatim** to the harness's `withModel` — orca does not normalise or validate
model ids, except that claude's bare `haiku` alias is sent as
`claude-haiku-4-5`, so a `claude:haiku` pin cannot land on a pricier tier when
the CLI resolves the alias. For example:

```properties
planningAgent = claude:opus
codingAgent = codex:gpt-5-mini
reviewAgent = opencode:anthropic/claude-haiku-4-5
```

Agent keys are read even when `flow(stackSettings = Some(...))` overrides the
stack commands — that override governs the stack portion only, and a malformed
project or global file still aborts the run either way. `setup` announces the
resolved roles and where each came from:

```text
agents: planning=claude:claude-opus-5-5[1m] (default), coding=codex:gpt-5-mini (project), review=opencode:<harness default> (global)
```

`<harness default>` marks a role where nothing pins a model, so the harness
picks one itself.

**Auto-discovery.** Discovery runs when the project file is absent or has no
stack line; discovered entries are appended below any existing content, so
agent lines are never touched. Delete the stack lines (or the whole file) to
re-run it. Discovery spends one cheap-model, read-only agent call inspecting
the repo, then writes the file and announces every guess in the event log:

```text
no .orca/settings.properties — discovering how to format, lint & test this project
  format = cargo fmt   # Cargo.toml; via rustfmt
  lint = cargo check --tests   # Cargo.toml
warning: stack settings: no test command — gate disabled
written to .orca/settings.properties — review and edit as needed.
```

Runs with an existing, stack-complete file — the steady state, including CI —
make no model call.

**Reviewer prompts.** Reviewers come from three tiers, read once per run before
setup like the settings files:

- **`{workDir}/.orca/reviewers/*.md`** — committed project reviewers.
- **`$XDG_CONFIG_HOME/orca/reviewers/*.md`**, defaulting to
  `~/.config/orca/reviewers/` — your own, across every project.
- The eight reviewers orca ships with.

A reviewer's identity is its filename stem — `.orca/reviewers/orca.md` is the
reviewer `orca` — compared case-insensitively. A file whose stem matches a
lower tier replaces it, keeping its position in the roster; anything else is
appended, sorted by name. Project beats global beats built-in, so
`.orca/reviewers/scala-fp.md` retunes the shipped `scala-fp` for this project
without changing how many reviewers run. A reviewer that adds a new name joins
both `allReviewers` and `minimalReviewers`; one that shadows a shipped reviewer
runs wherever that shipped reviewer runs, so shadowing `scala-fp` leaves
`minimalReviewers` — correctness, clarity, tests — alone. The picker narrows
per task as usual.

Each file is frontmatter plus a body, the same shape the shipped ones use:

```markdown
---
description: Checks the project's own layering rules.
files: \.scala$
---

## Scope

Review only the layering of the changed files...
```

`description:` is required and must be a single line — the reviewer-picker
decides from it. The value is the rest of that line: a YAML block scalar (`>`,
`|`, `>-`, `|-`) or a value wrapped onto the next line aborts the run.
`files:` is optional: a regex matched against each changed path, so the
reviewer is only offered when the change touches a file it applies to. The body
is the reviewer's system prompt. A `name:` key, if present, is ignored.

`README.md` and any `_`-prefixed name sit in the directory as documents. Every
other `.md` must parse as a reviewer: a missing or unterminated frontmatter
block, a missing `description:`, an empty body, an invalid `files:` regex, or
two files claiming one name abort the run before any tree mutation, naming every
bad file at once — a reviewer silently dropped from the roster would read as a
clean review. A symlinked prompt aborts too, but only in `.orca/reviewers/`:
that directory is committed and orca runs against repos it did not write, while
the global tier is your own config home and is read through links like
`settings.properties` beside it. When a tier contributes
anything, the run says so:

```text
discovered reviewers: orca (project); scala-fp (project, shadows built-in)
```

<details>
<summary>Discovery internals and the <code>.orca/</code> directory</summary>

Every discovered command cites the file that evidences it, and two checks run
before the file is written: the command's executable must be on `PATH`, and the
cited evidence file must exist. A command failing either is kept only as a
comment (`# skipped: lint = just check (just: not found on PATH)`), never run
silently; a key left with no command gets a live `key = off` line. A discovery
failure (backend unavailable, invalid output) aborts the run rather than writing
a "gates off" file.

`.orca/` is committed by default: settings and each run's progress log
(`runs/<key>.progress.json`) ride the branch, while machine-local state lives
under `.orca/cache/`, which writes its own `.gitignore`: each run's durable
session records (`runs/<key>.sessions.json`, whose backend ids mean nothing in
another checkout), and per attempt — one process — a manifest
(`attempts/<id>.manifest.json`, what `orca continue` lists) and a cost log
(`attempts/<id>.cost.jsonl`: one line per agent turn with agent, role, model,
stage, token usage and cost — the per-agent and per-model detail the closing
summary leaves out). The cache is safe to delete; only the newest 20–40
attempts are kept. If
your `.gitignore` covers all of `.orca/`, every run warns to remove that line so
settings can be committed — the cache stays ignored on its own.

</details>

Within a flow body the resolved stack settings are available as
`summon[FlowContext].stackSettings` — a `StackSettings(format, lint, test:
List[String])`. The `test` commands are not consumed by `reviewAndFixLoop` (the
lint gate stays deliberately cheap); they're there for a flow's own verification
stages.

### Sessions

Three rungs, by how long the conversation must live — the handle you hold tells
you which one you're on:

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

- **Durable — `agent.session(name, seed)`.** A get-or-create keyed by the
  `name` and the stage the call sits in, returning a `FlowSession` handle that
  survives crash/resume: the same key resumes the same session (with a warning
  if this call's seed differs, rather than silently resuming the wrong one).
  `name` is the role, and what `orca continue <name>` matches. The stage half is
  implicit — a per-task loop mints `implementer` inside each task's stage and
  gets one session per task, with nothing to name them by hand. Two stages can
  therefore never reach one conversation, and minting one name twice in the same
  stage is an error rather than silent sharing: give each its own `stage(...)`,
  or rename one.
  Rename the stage and the key moves with it, so a re-plan that rewords a task
  gives that task a fresh session primed from the seed rather than resuming the
  old wording's conversation. Mint it where it is used: inside the stage that
  drives it, or outside every stage when several stages share one session. What
  you cannot do is return a handle from one stage as its result and drive it in a
  later one — `FlowSession` has no `JsonData`. Minting and running both happen on
  the flow thread. The record behind the handle is machine-local, not branch
  history: it lives in `.orca/cache/`, so the stage that minted it can fail and
  its retry still resumes the same conversation.
- **Ephemeral — `agent.chat()`.** A `Chat` handle continuing one conversation
  across `.run` calls *within this run only* — no seeding, no persistence. Runs
  need only the shared `InStage` capability, so chats work inside a
  `Par.mapUnordered` fork: parallel reviewers each holding a multi-turn
  conversation is the canonical use. `session.chat` is a durable session's
  conversation as an ephemeral chat — the escape hatch for follow-ups from a
  fork (turns are not persisted; one live continuation at a time).

```scala
val session = agent.session("implementer", seed = plan.brief)
session.run(task.description)

val chats = Par.mapUnordered(4)(reviewers): r =>
  val c = r.chat()
  c.run(s"review the diff: $diff")
  c                       // keep the conversation for a later re-review turn
```

The `seed` is the essential context to rebuild the agent — typically the **plan
brief**, or the issue body when there is no brief. A fresh session is primed
with it on first use; if the backend lost the conversation on resume, the
session is re-seeded (with a warning: history is gone, only the seed plus a
preamble naming completed stages are rebuilt), while a live session continues
with its full history — told once, on this run's first turn against it, that the
tree holds only what earlier stages committed, since a conversation a previous
run opened remembers writing files that are no longer there.

**How long a session should live.** A backend conversation is re-sent whole on
every API call it makes, so what a session costs grows with everything it has
already done. Scope one to a unit of work — a task, a review stage — not to the
run: the shipped flows mint a session per task and another for the final review,
and each new one is primed from its seed and the completed-stage preamble.

`agent.cheap` returns the backend's cheap/fast variant (claude → haiku, codex →
mini, gemini → flash, opencode → anthropicHaiku, others → self) — used by the
runtime for branch naming and default commit messages.

**Backend swaps across runs.** If a settings edit changes a role's agent
between runs (e.g. `codingAgent = codex` becomes `codingAgent = claude`), a
session recorded under the old backend isn't resumed against the new one —
orca mints a fresh session from the seed and warns.

## Authoring rules

Mutations outside a stage body are compile errors (see [Side effects happen
inside stages](#side-effects-happen-inside-stages)). The rules below are the
structural conventions you choose to follow as a flow author.

1. **Reads outside, mutations inside.** Only side-effecting work goes in a
   stage. Pure reads (`git.uncommittedDiff`, `gh.readIssue`, `fs.read`, `gh.waitForBuild`)
   run outside stages — staging them wastes commits and checkpoints.
   `agent.session(name, seed)` is neither — it records a session — so put it
   where the session is used (see [Sessions](#sessions)).

2. **Push lives in a later stage than the edit that produced it.** A stage
   commits only on completion: a `git.push()` in the same stage as the edit
   would push nothing (the edit isn't committed yet). The push must be in a
   *separate, later* stage:

   ```scala
   stage("Write failing test"):
     session.run("Write the failing test …")    // commits on completion

   val pr = stage("Push + open PR"):   // LATER stage — the test commit exists now
     git.push().orThrow
     gh.createPr(title = …, body = …).orThrow
   ```

3. **One commit per stage.** Each stage produces exactly one commit (code
   changes + the progress-log entry), made by the runtime when the stage
   completes.

4. **Idempotent external effects, each in its own stage.** Put each PR-open,
   comment-post, or push in a dedicated stage so it's checkpointed.
   `gh.createPr` is idempotent by branch (an open PR is reused, not duplicated)
   and `gh.upsertComment(target, marker, body)` edits a prior comment carrying
   `marker` in place — so if a crash re-opens the stage on resume, the re-run
   reuses the PR/comment instead of duplicating it. Use
   `orcaCommentMarker(userPrompt, purpose)` so the marker is unique to this run.

5. **Name stages descriptively.** The stage name appears in the event log, the
   commit message (when no override is provided), and the progress preamble on
   resume. A name like `"Push + open PR"` lets a reader (and the resuming agent)
   understand the checkpoint without reading code.

## Experimental: capabilities & compile-time concurrency checking

Orca gates side effects behind three capability tokens. You normally never
construct one — `stage(...)` bodies provide them, and a missing token is a
compile error with a message telling you where the call belongs:

| Capability | Kind | Gates | Provided by | Misuse caught by |
|---|---|---|---|---|
| `InStage` | shared (`caps.SharedCapability`) | LLM runs (`agent.*.run`, `session.run`) | `stage(...)` bodies | missing-given compile error |
| `WorkspaceWrite` | exclusive (`caps.ExclusiveCapability`) | git/`gh` writes, `fs.write`, progress-log writes | `stage(...)` bodies | missing-given compile error + a runtime owner-thread check (never cross a `fork`) |
| `FlowControl` | exclusive (`caps.ExclusiveCapability`) | starting stages, minting sessions | the `flow(...)` body (not forks) | missing-given compile error + a runtime owner-thread check |

(`FlowContext` — reads and event emission — is deliberately *not* a capability:
it is thread-safe and forks receive it freely.)

The runtime always guards this at run time — a fork that calls
`stage(...)`/`session(...)` or makes a workspace write fails immediately, a second `flow(...)` in the same
working tree is refused, an agent used after its flow ended throws — so you get
the safety without any setup.

<details>
<summary>Compile-time checking (Scala's experimental capture checking)</summary>

The shared/exclusive split is [capture
checking](https://docs.scala-lang.org/scala3/reference/experimental/cc.html)
vocabulary. Beyond the always-on runtime guards, enforcement moves to compile
time in two more places:

- **Inside the library:** orca's own parallel code (the reviewer fan-out) is
  compiled under capture + separation checking, so a change that captured a
  `WorkspaceWrite` into that fan-out would not compile (pinned by a compile-time
  test suite).
- **Opt-in, in your script:** add the two language imports to have the compiler
  check *your* code too — today that enforces, e.g., that a custom
  `ReviewerSelector`'s per-round function stays pure:

  ```scala
  import language.experimental.captureChecking
  import language.experimental.separationChecking
  ```

  Full fork-boundary checking in scripts arrives when Ox itself adopts capture
  checking; until then the runtime guard covers that case.

The imports cost nothing when omitted — scripts without them compile and run
identically (see ADR 0018 §6).

</details>

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

Every cell returns `Sessioned[<result>]` — the result paired with the
(ephemeral) `Chat` that produced it. Continue that conversation in-run
(`chat.run(task)`; continuations have write access), or `.value` it and start a
fresh, durable implementer session via `agent.session("implementer", seed =
plan.brief)` — the chat does not survive a crash/resume, so every
shipped example takes `.value`. Destructure when you want both: `val
Sessioned(chat, plan) = Plan.autonomous.from(...)`.

From a `Sessioned[Plan]`, an optional `.reviewed()` step refines the plan
before implementing — the planner critiques its own draft, read-only, producing
an improved `Plan`. Chain it: `Plan.autonomous.from(...).reviewed().value`.

`assessThenPlan` returns a `Verdict`: `Verdict.Proceed(plan)` to implement, or
`Verdict.Rejection(kind, body)` — a follow-up question, critique, or rebuff the
caller surfaces back to the reporter. `triage` returns a `Triage` sum type the
caller pattern-matches (`NotABug` / `Untestable` / `Testable`).

Review utilities, available via `import orca.review.*`:

| Method | Use |
|---|---|
| `lint(commands, agent, instructions?)` | Run shell lint commands (in order, each via `bash -c`; every one runs even if an earlier one fails) and have `agent` summarise their labelled, concatenated output as a `ReviewResult`. Short output is inlined into the prompt; anything larger is written to a file under `.orca/cache/` for the agent to read, so unbounded output can't overflow the context. |
| `lint(commands, summariser, instructions)` | As above, but summarising into an existing `Lint.summariser(agent)` conversation instead of a fresh one per call, so a gate run several times within one stage resumes the session rather than re-establishing it each round. Stop reusing a summariser once it has reported: it can repeat those findings on a later call whose commands no longer show them. `reviewAndFixLoop` does this for you. |
| `reviewAndFixLoop(coderSession, reviewers, task, userRequest?, ..., formatCommands?, lint?, checks?, maxFixTurns?, fixInstructions?)` | Run reviewers against `task: Task`, collect their findings, hand them to the `coderSession` (a `FlowSession`) to fix, re-evaluate. Reviewers are asked to report only what they believe should be fixed, and every finding they report reaches the fixer — nothing filters them in between. Reviewers see the task's title and description under separate labels, plus the user's request — the run's prompt by default, or `userRequest` when the prompt is only a pointer, like an issue reference. Keeping them apart is what lets a reviewer report a finding against the planner's choice rather than only against the code. A flow with no planning stage passes its prompt as the title and an empty description. Halts when reviewers come back clean, the fixer reports no fixes, or `maxFixTurns` fix turns have run (default 3, so up to four review rounds). Every exit names the findings it leaves open and why each is still open. Whatever is still open at that point — the findings the fixer declined, didn't account for, or that were first reported in the round that hit the cap — comes back in the returned `OpenFindings` with a reason. `formatCommands: Configured[List[String]]` runs before each review round; `lint: Configured[Lint]` runs alongside the reviewers each round — both default to the project's [stack settings](#settings), see below. `checks: List[ReviewCheck]` (default none) run after formatting and before the reviewers, see below. |
| `reviewThenFix(coderSession, reviewers, task, userRequest?, formatCommands?, lint?)` | One round of the above and, if it found anything, one fix turn — then done. Nothing re-reviews a reviewer finding, so the fixer's claim that it fixed one is taken on trust; the lint gate is the exception, re-run over the fixer's edits and given one more fix turn if it still fails. Reviewers are picked once (`ReviewerSelector.agentDriven`) and the change set is the enclosing stage's, as above. What the fixer declined, what it never reported on, and what the lint gate still fails on, come back in the returned `OpenFindings` with a reason. Use it per task where a later stage reviews the same code again — a whole-run `reviewAndFixLoop`, below — and pay for the loop where nothing else re-reviews the fixes. |
| `ReviewCheck` | A check written in Scala — a benchmark, an HTTP probe, a scripted assertion: `name` plus `evaluate(): ReviewResult`. Pass it in `reviewAndFixLoop`'s `checks`; its findings go to the fixer with the reviewers'. |
| `OpenFinding.custom(title, reason, location)` | An open finding a flow records itself — say, a gate it runs outside the loop still failing. Add it to `OpenFindings` for the PR body, or pass it in `priorOpenFindings` so a loop's reviewers see it. |
| `allReviewers(base)` | Every reviewer in the run's catalog (the eight canonical ones — code-functionality, test, readability, code-structure, simplicity, performance, security, scala-fp — plus whatever `.orca/reviewers/` and the global tier add, see [Settings](#settings)) as `ReviewerAgent`s: each one its `Reviewer` definition plus a read-only agent built from `base`. |
| `minimalReviewers(base)` | Universally-applicable subset (code-functionality, readability, test) plus every discovered reviewer, same shape. Pair with the default LLM-driven selector when the full set is overkill. |
| `reviewerCatalog` (in-body accessor) | The run's resolved reviewer definitions — `.all` and `.minimal` are what the two above build from. Filter it to pick a subset yourself. |

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
the summariser agent bundled in one value (`Lint(commands: List[String],
agent)`). An empty list resolves to no gate at all: `FromSettings` over empty
settings behaves exactly like `Off`. A script that omits `lint` gets a lint gate
whenever the target project's settings define one; for format-only, pass `lint =
Configured.Off`.

Each round runs its `checks` one at a time, after the format commands and
before the reviewers and the lint gate start, so a check that builds or times
the code has the machine to itself. A check must not modify sources. Keep a
finding's title the same across rounds and put measurements in its description:
the loop recognises a finding it already holds as open by its title and file.
With no reviewers, the loop just evaluates the check and fixes:

```scala
val benchmark = new ReviewCheck:
  def name = "benchmark"
  def evaluate()(using ctx: FlowContext, ev: InStage): ReviewResult =
    val ms = os.proc("./bench.sh")
      .call(cwd = ctx.workDir, stderr = os.Pipe).out.trim().toInt
    if ms <= 200 then ReviewResult.empty
    else ReviewResult(List(ReviewFinding(Title("Request too slow"),
      s"p99 is $ms ms; the target is 200 ms", location = None,
      suggestion = None, reopens = None)))

stage("Speed up"):
  reviewAndFixLoop(
    coderSession = session,
    reviewers = Nil,
    task = Task(Title("Make requests faster"), ""),
    lint = Configured.Off,
    checks = List(benchmark)
  )
```

The whole loop is one stage, so one commit. When each iteration is long, write
the loop in the flow instead, one stage per iteration calling
`coderSession.run`, so a resume picks up at the last finished iteration.

The change set reviewers are shown — and that the selector picks from — is
everything the enclosing `stage` has produced since it began, so it is the same
whether or not the coding agent committed its own work along the way. It is
re-sampled each round and sent to every reviewer that runs, resumed ones
included, so each round's reviewers see the fixes made before it. Pass
`diff = ReviewDiff.Pinned(...)` to pin it instead: reviewers are then not told a
base commit, the selector's changed-file list is scraped from the diff text, and
every later round finds the same text, so a resumed reviewer is told there is no
new change set.

`diff = ReviewDiff.WholeRun` widens it to everything the run has changed since
it started — since the commit HEAD pointed at when the run bound its branch,
recorded in the progress log — so a stage placed after the per-task work
reviews the whole branch, earlier stages' commits included. Reviewers are told
the change set spans every stage. A run whose log records no usable commit (a
log from before orca recorded one, or one whose commit no longer sits behind
HEAD after a rebase) has no base: the call says so in a step and returns without
reviewing.

That is the final-review half of the shape every task-based built-in flow uses
— `reviewThenFix` per task, then this once:

```scala
stage("Final review"):
  reviewAndFixLoop(
    coderSession = session,
    reviewers = allReviewers(reviewAgent),
    task = Task(Title("The whole planned change"), plan.brief),
    diff = ReviewDiff.WholeRun,
    maxFixTurns = 5
  )
```

The cap is above the library default of 3 because nothing reviews again after
this loop. Each flow hands what it returns to its PR step
(`openPrIfGitHub`/`openPrFromBranch`), which lists every finding still open in
the PR body.

A change set past 128 KiB is cut down before it is sent: the reviewer gets as
many whole files as fit, then a list naming every other changed file with its
line counts, and reads those files itself. Without that, the largest change sets
make a request no model can accept. A pinned diff is sent as given.

`reviewAndFixLoop`'s `reviewerSelection` defaults to `ReviewerSelector.default`,
which narrows twice: a picker LLM on `reviewAgent`'s cheap tier chooses from the
supplied list for round one, seeing each reviewer's description and the changed
file paths; every later round then re-runs only the reviewers that reported a
finding in the previous one. A reviewer that stays quiet stops costing a turn
per round — the trade-off is that it won't see the fixes made after it stopped.
If narrowing would leave no reviewer at all (everyone quiet, while a lint finding
keeps the loop going), the round's full selection runs again and a step says so.

| Selector | Behaviour |
|---|---|
| `default` | The above: `narrowingAcrossRounds(agentDriven)`. |
| `allEveryRound` | The whole supplied roster, every round; no picker. |
| `agentDriven` | Pick once with `reviewAgent.cheap`, replay that pick every round. |
| `agentDriven(agent, instructions?)` | As above with a chosen picker model and brief. |
| `narrowingAcrossRounds(base)` | Adds the per-round narrowing over any `base`. |

A reviewer declaring a `files:` pattern in its frontmatter (of the shipped set,
only `scala-fp`) is offered to the picker only when a changed file matches it —
unless nothing is known about the change set, in which case it stays eligible.
The selector reads each reviewer's name, description and pattern off its
`Reviewer`, so your own reviewers are described and gated the same way.

To swap or extend the reviewer set for one project, drop `.md` files in
`.orca/reviewers/` — no code changes (see [Settings](#settings)). To do it from
the flow, compose your own `List[Reviewer]` from `reviewerCatalog.all` (the
run's resolved set), `ReviewerPrompts` (the shipped entries alone), and/or your
own `Reviewer(ReviewerSlug(name), description, systemPrompt)`, then turn it into
`ReviewerAgent`s with `buildReviewers(base, list)`.

PR utilities, available via `import orca.pr.*`:

| Method | Use |
|---|---|
| `summarisePr(agent, diff, context?, instructions?)` | Fold a branch diff into a `PrSummary(title, body)` for `gh.createPr`. `context` is an optional preamble (originating issue link, user prompt, etc.) the model anchors the description to. A diff too large to send is cut short. Use a cheap model (`claude.cheap`, `codingAgent.cheap`). |
| `openPrFromBranch(summarisingAgent, openFindings, title?, body?, context?, instructions?): PrHandle` | Push the feature branch and open a PR for it, as three stages: push → summarise → create. Requires a GitHub remote and a logged-in `gh` — without either the run fails. `openFindings` is the `OpenFindings` the run's final review returned; each entry is listed under "Open review findings" as its title, where it points if the reviewer named a place, and the reason, verbatim, after a line saying so if the review was skipped (none open and not skipped: no section). The same section is printed to the run output, also when the PR fails. `title`/`body` rewrite the generated text. `context` defaults to the run's user prompt, and then the summariser adds a `Closes #N` line per issue the prompt says to fix; a flow that passes `context` adds its own `Closes` line through `body` (`body = s => s"${s.body}\n\nCloses #42."`). Opening the PR is a top-level step of a flow and this runs its own stages, so it does not compile inside one. |
| `openPrIfGitHub(summarisingAgent, openFindings, title?, body?, context?, instructions?): Option[PrHandle]` | Probes `gh.availability` before its push stage runs (a resume that replays the push skips it), then runs `openPrFromBranch`'s push → summarise → create when the checkout is on GitHub. Where it isn't — no remote, a remote that isn't GitHub, a GitHub `gh` cannot reach, a run that changed no code, or a push/create the remote refuses — it emits one `Step` saying why, returns `None`, and the run finishes. The open findings are printed to the run output either way. A resume replays what its push and create stages recorded, a refusal included. The step every code-producing built-in flow ends with; like `openPrFromBranch`, it does not compile inside a stage. |
| `bodyWithOpenFindings(body, open)` | `body` with the "Open review findings" section appended, or `body` unchanged when nothing is open and the review ran — the assembly `openPrFromBranch`/`openPrIfGitHub` use, for a flow that writes its own PR body (`gh.updatePr`). |
| `reportOpenFindings(open)` | Print the "Open review findings" section to the run output; nothing when nothing is open and the review ran. `openPrFromBranch`/`openPrIfGitHub` do this themselves, before their PR step; a flow that writes its own PR body calls it before its PR step. |
| `recordOpenedPr(pr)` | Record the PR's URL as the run's published work, so the run hands the checkout back on the branch it started from and the closing summary names the PR. Only for a flow that opens its PR with a bare `gh.createPr` — `openPrFromBranch`/`openPrIfGitHub` record it themselves. Call it inside the stage that opened the PR (it needs that stage's `WorkspaceWrite`): the stage's commit carries the record, and a resume reads it back without re-running the body. |

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
- `orca.review.ReviewLoopPrompts` — `Fix`, `SelectReviewers`, `SummariseLint`
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
  agent generates in one round-trip. `epicId` is a kebab-case identifier for the
  plan itself (heads its markdown render) — NOT the git branch name; the flow
  derives and announces its own branch separately (see
  [`BranchNamingStrategy`](#the-flow-lifecycle)). `description` is the planner's
  epic summary; `brief` is a concise codebase briefing always included (feed it
  to `agent.session("implementer", seed = plan.brief)`, which threads it as the
  seed). `taskPrompt(task)` prepends the brief to a task's
  description.
- **`orca.plan.Task(title, description)`** — `title` is the human-readable label
  shown in the event log.
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
- **`orca.FlowSession`** — durable, resumable session handle returned by
  `agent.session(name, seed)`. Call `.run(prompt)` or `.resultAs[O].run(input)`
  on it to drive the agent, with automatic seed/preamble replay (when the
  backend conversation isn't live) and resume-wire-id persistence.
  `session.chat` is its conversation as an ephemeral `Chat` (the fork-side
  escape hatch).
- **`orca.agents.Chat[B]`** — ephemeral multi-turn conversation handle from
  `agent.chat()`: tool-using and workspace-editing like any agent turn ("chat"
  names its lifetime, not its powers), in-run only, fork-safe. Also carried by
  `Sessioned` for planning-conversation continuations.
- **`orca.Title`** — opaque `String` alias for short labels (`Task.title`,
  `ReviewFinding.title`); `Title("…")` to construct, `.value` to read.
- **`orca.tools.PrHandle`** — handle to an open pull request (`host`, `owner`,
  `repo`, `number`), returned by `gh.createPr`. Build one with
  `PrHandle.from(host, owner, repo, number)` (a `Left` names the field that is
  not a valid host, owner, repo or PR number) or `PrHandle.fromUrl(url)`.
  `host` is `github.com` or a GitHub Enterprise hostname, and every `gh` call
  taking the handle is routed to it. Has a `JsonData` (it travels as its URL)
  so a stage can record it: a push-and-open-PR stage is the checkpoint before a
  CI wait. `IssueHandle` carries no host, so the issue flows read their issue
  from gh's default host (`GH_HOST`, else the host gh is logged in to).
- **`orca.tools.GitHubAvailability`** — what `gh.availability` answers with.
  `Available(host, owner, repo)`: the repository gh resolves, on github.com or a
  GitHub Enterprise host. `Unavailable(why)`: no PR can be opened; `why` is a
  `GitHubUnavailable` — `NoRemote` (no `origin`), `NoHost(remote)` (`origin`
  has no host, a local path), `NotGitHub(host)` (gh has no login for that host,
  so a GHES host needs `gh auth login --hostname <host>`), `Unreachable(host,
  reason)` (the host is GitHub, but gh could not answer for it — `reason` is
  gh's own words), or `GitUnusable(reason)` (git itself could not be run, so
  nothing is known about the checkout). `why.explanation` renders that as one
  line to put a flow's own next action after.
- **`orca.pr.PrSummary(title, body)`** — what `summarisePr` returns. The two
  fields feed `gh.createPr(title = …, body = …)` directly.
- **`orca.review.ReviewFinding` / `ReviewResult`** — what reviewer agents
  return. A finding carries a `title` (shown), a long `description` (sent to
  the fixer), an optional `location`, and `reopens`: the `FindingId` of the
  still-open finding it reports again, if any.
- **`orca.review.OpenFindings(findings, skipped)`** — accumulated
  `OpenFinding(id, title, reason, location)` entries surfaced by
  `reviewAndFixLoop` once it halts: every finding the run did not resolve, each
  with where it points and an `OpenReason` — `Declined(text)` (the fixer's own
  words), `NoFixes`, `Unaccounted`, `CapReached(max)`, `LintStillFailing` or
  `Custom(text)` (from `OpenFinding.custom`).
  `reason.describe` is the sentence shown to a reader. `id` (`FindingId`) is
  what entries merge by across rounds; two findings sharing a title stay two.
  `skipped` is `Some(SkippedReview)` when the review never ran.
- **`orca.StackSettings(format, lint, test)`** — the resolved per-project
  tooling commands (each field a `List[String]`, run via `bash -c`; empty = task
  disabled). Resolved once per run — see [Settings](#settings) — and read back
  via `summon[FlowContext].stackSettings`; pass `flow(stackSettings =
  Some(...))` to pin it.
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
| `▸` | The prompt sent to an agent |
| `●` | Assistant prose |
| `⏺` | Tool call (path / command / query in grey). A read-only call shows as a bare `⏺ read`, with no filename and no agent name, so a burst of them folds into one line; the trace file has both |
| `⎿` | How many times the line above repeated (`⎿ ×12`) |
| `✖` | Error |
| `?` | Approval request, or a question for you (interactive calls only) |
| `!` | Caveat about what Orca can enforce for this run (never indented under a stage) |

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

- **Launcher (zero config):** `flow(OrcaArgs(args), opencode = Some(w =>
  OpencodeAgents.default(w, OpencodeLauncher.ollama("qwen3-coder"))))`. Orca
  starts the server via `ollama launch opencode`, which injects Ollama's
  provider config and pins that one model — use bare `opencode`, no `withModel`.
  Needs the `ollama` CLI and the model pulled.
- **Manual config:** declare an `ollama` provider in
  `~/.config/opencode/opencode.json` (baseURL `http://localhost:11434/v1`, your
  models, `num_ctx` raised for tool use), then `opencode.withModel("ollama",
  "qwen3-coder")`. Supports several models and per-turn switching.

</details>

## Getting set up

Orca is published to Maven Central — `scala-cli` fetches the artifacts on first
run:

```bash
scala-cli run --workspace "$(mktemp -d)" implement.sc -- "your task here"
```

`--workspace` keeps scala-cli's build output out of your repository; without it
you get a `.scala-build` directory next to the script.

For a guided start, install [Orca Shell](#orca-shell) instead: its first-run
wizard configures the role agents and models for you.

## Orca Shell

Orca Shell is an interactive terminal front-end for the same flow scripts: a
first-run wizard picks a harness and model for each of the
planning/coding/review roles — writing the same global `settings.properties`
described under [Settings](#settings) — then a menu lets you discover flows
(project, global, and built-in), run one, view or edit its source, create a new
flow (or fork an existing one) with the configured role agents' help, or
continue a session left by a previous run. It launches flows the same way
`scala-cli run` does — direct `scala-cli run flow.sc -- "task"` keeps working
unchanged.

### Command-line usage

Every action in the interactive menu also has a scriptable subcommand — `orca`
with no arguments starts the interactive shell; `orca <command> ...` runs one
action non-interactively and exits.

| Command | Key flags | Does |
|---|---|---|
| `orca run <flow> [task]` | `--prompt <task>` (the task, for text starting with `-`; not with the positional), `--verbose` (stack trace on abort), `--branch <name>` (create the run's branch under this name; refused with `--skip-branch`), `--skip-branch`, `--keep-changes` (leave uncommitted files in place), `--worktree` (run in a git worktree of this repository), `--honor-pin` (use the flow's own pinned orca version) | run a flow, propagating its exit code; task is read from stdin when omitted and piped |
| `orca view <flow>` | `--plain`, `--color` | print a flow's source (highlighted when stdout is a terminal) |
| `orca edit <flow>` | `--to project\|global` | open a flow in `$VISUAL`/`$EDITOR`/vi (`--to` required to customize a built-in) |
| `orca create "<goal>"` | `--name <file>`, `--global` | author a new flow: the built-in `simple.sc` flow writes it in an isolated sandbox with the configured role agents; `--name` is auto-derived when omitted. The sandbox is a fresh repository with no remote, so the flow's closing PR step opens nothing and says so |
| `orca fork <source> "<changes>"` | `--name <file>`, `--global` | fork an existing flow, the same way |
| `orca continue [selector]` | `--list`, `--json` | resume a recorded harness session (no selector = newest); `selector` is an id from `--list` (it keeps naming the same session while other runs record theirs), a session name, or a branch — a name matching several sessions in one working tree resumes the most recent of them; a selector matching both a name and a branch is refused |
| `orca config` | `--planning-agent`, `--coding-agent`, `--review-agent`, each taking `harness[:model]`; or `--edit project\|global` | show the configured role agents, set any subset, or hand-edit that tier's settings file in `$VISUAL`/`$EDITOR`/vi (created from its template if absent) |
| `orca list` | `--json` | list discovered flows across the project/global/built-in tiers |
| `orca clear-stack` | `--yes` | clear discovered stack settings so the next flow run re-detects them |

`create`, `fork`, `edit`, `continue`'s resume, and `config --edit` each need a
real terminal and error cleanly if run without one; `run`, `view`, `list`,
`config` (without `--edit`), and `clear-stack --yes` work fine piped or in CI.

Examples:

```bash
orca run implement.sc "add a rate limiter to /login"
echo "add a rate limiter" | orca run implement.sc
orca list --json | jq -r '.[].name'
orca create "add a token-bucket limiter" --name rate-limit.sc
orca continue              # resume the last session
orca continue --list
orca continue feat/rate-limiter
orca config --coding-agent codex
orca config --review-agent claude:sonnet
orca view implement.sc
```

Run `orca --help` for the full command list, or `orca <command> --help` for a
command's own flags. Exit codes: 0 success, 1 action failure, 2 usage error —
`orca run` propagates the flow's own exit code, which makes it CI-friendly.

Install it with:

```bash
curl -fsSL https://raw.githubusercontent.com/VirtusLab/orca/master/install.sh | bash
```

The script does exactly two things:

1. If `scala-cli` isn't on your `PATH`, it downloads and runs scala-cli's
   official installer (which places scala-cli in its own versioned location and
   updates your shell profile; scala-cli then manages its own JVM).
2. It writes the `orca` executable to `~/.local/bin/orca` — a short launcher
   script that runs the latest released `orca-shell` via `scala-cli`. Nothing
   else is downloaded at install time; the artifacts are fetched on the first
   `orca` run, and the launcher never needs a version bump.

Add `~/.local/bin` to your `PATH` if the installer says it isn't there yet, then
run `orca`.

To avoid installing anything, or to pin a version (e.g. in CI), run the shell
directly instead. The pinned form works from the first release that includes the
shell; the version below always tracks the latest release. `--workspace` keeps
scala-cli's own build metadata out of the current directory (it lands under the
given directory instead):

```bash
scala-cli run --workspace "${XDG_CACHE_HOME:-$HOME/.cache}/orca/shell/workspace" --jvm 21 --quiet --verbose --dep "org.virtuslab::orca-shell:0.1.9" --main-class orca.shell.Main
```

## Documentation

- [`adr/`](adr/) — architecture decision records. [ADR
  0018](adr/0018-stage-bound-flow-runtime.md) describes the current stage-bound
  runtime; the ADR index covers module layout, backends, the flow DSL, and
  reviewers.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — building, testing, and running a
  locally modified orca.
- [`AGENTS.md`](AGENTS.md) — internals, architecture, and coding conventions;
  the same file AI assistants pick up.

## License

Apache 2.0 — see [LICENSE](LICENSE).

## Copyright

Copyright (C) 2026 VirtusLab [https://virtuslab.com](https://virtuslab.com).
