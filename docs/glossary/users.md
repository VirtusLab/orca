# Glossary for users

The words these docs use. Orca's internal vocabulary is in the
[developer glossary](developers.md).

## Flows and runs

- **flow** — a Scala script whose body is `flow(OrcaArgs(args)): ...`. See
  [Writing your first flow](../authoring/tutorial.md).
- **flow args** — `OrcaArgs`: the prompt and the command-line flags.
- **prompt** — the user's input text, `userPrompt` in a flow body.
- **stage** — `stage(name)(body)`: a unit of work that commits on completion
  and is skipped on resume. See [Stages](../authoring/stages.md).
- **plan** — `orca.plan.Plan`: the task list the planning agent (the
  **planner**) produces. See [Planning](../authoring/planning.md).
- **plan task** — one `orca.plan.Task` of a plan. The **plan brief**
  (`Plan.brief`) is the planner's codebase briefing.
- **run** — one prompt's flow execution, across every process it takes to
  finish. See [Branches, resume and worktrees](../using/run-lifecycle.md).
- **attempt** — one of those processes: one `orca run`, one `flow(...)` call.
- **re-run / resume** — another attempt of an unfinished run, with the same
  prompt. It skips the stages already recorded.
- **progress log** — `.orca/runs/<key>.progress.json`, committed with each
  stage: which stages finished, and their results.
- **run target** — where a run works: a new branch (the default), the current
  branch (`--skip-branch`) or a worktree (`--worktree`).
- **worktree** — a second checkout of the repository under `.orca/worktrees/`.

## Agents and conversations

- **harness** (also **backend**) — the coding-agent CLI Orca drives: `claude`,
  `codex`, `opencode`, `pi` or `gemini`.
- **agent** — a harness with a model and tool settings: `claude`, `codex.mini`,
  `codingAgent`, and so on. See [Backends](../api/backends.md).
- **role agent** — `planningAgent`, `codingAgent` or `reviewAgent`, resolved
  from [settings](../using/settings.md).
- **cheap tier** — `agent.cheap`: the harness's cheaper model.
- **turn** — one prompt to an agent and its reply.
- **conversation** — the history a harness keeps across the turns of one chat
  or session.
- **one-shot / chat / session** — `agent.run` (one turn), `agent.chat()` (a
  conversation for this attempt) and `agent.session(name, seed)` (a
  conversation that survives resume). See
  [Talking to agents](../authoring/talking-to-agents.md).
- **session name / session key** — the name is the session's role
  (`"implementer"`), which `orca continue` matches. The key is the name plus the
  stage the session is created in.
- **seed / re-seed** — the context a session starts from, usually the plan
  brief. A session whose conversation is lost is re-seeded: started again from
  its seed.
- **structured output** — `resultAs[O]`: a reply parsed into an `O`, which
  needs a `JsonData[O]`.

## Review

- **reviewer** — a prompt saying what to look for, paired with a read-only
  agent.
- **reviewer catalog** — `reviewerCatalog`: every reviewer a run can use. See
  [Custom reviewers](../using/reviewers.md).
- **roster** — the reviewers one review call is given.
- **reviewer picker** — the cheap agent that chooses, from the roster, which
  reviewers run for a task.
- **review round** — one pass of the picked reviewers, the lint gate and any
  checks over the change. See [Review and fix loops](../authoring/review.md).
- **fix turn** — the coder session's `.run` that fixes a round's findings.
  `maxFixTurns` caps how many.
- **finding** — a problem a reviewer, the lint gate or a check reported.
- **declined finding** — a finding the fixer refused, with a reason.
- **open finding** — a finding the review ended without resolving.
- **`OpenFindings`** — what a review returns: the findings it left open. See
  [Data structures](../api/data-structures.md#review).
- **gate** — a stack command: `format`, `lint` or `test`. The lint gate runs
  each review round.
- **stack settings** — the project's gate commands, from
  `.orca/settings.properties`.
- **`Configured`** — how a review call takes a gate: from settings (the
  default), off, or a given value. See
  [Data structures](../api/data-structures.md#settings).

## Capabilities and tool limits

- **capability** — a compile-checked token a call needs. `InStage` (agent
  calls) and `WorkspaceWrite` (git, `gh` and file writes) come from a
  `stage(...)` body; `FlowControl` (starting stages, creating sessions) from
  the `flow(...)` body. `FlowContext` (reads) is not one. See
  [Capabilities](../authoring/capabilities.md).
- **fork** — a function running in parallel under `Par.mapUnordered`. See
  [Stages](../authoring/stages.md#parallel-work).
- **`ToolSet`** — which tools an agent has: `ReadOnly`, `NetworkOnly`, `Full`
  (the default) or `NoTools`. **Enforcement** is how strictly each harness
  holds that limit. See [Choosing agents](../authoring/choosing-agents.md).
