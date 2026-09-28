# Glossary for users

This glossary defines the words these docs use. Orca's internal vocabulary is
in the [developer glossary](developers.md).

## Flows and runs

- **flow** — A flow is a Scala script whose body is `flow(OrcaArgs(args)): ...`.
  See [Writing your first flow](../authoring/tutorial.md).
- **flow args** — The flow args are the `OrcaArgs`: the prompt and the
  command-line flags.
- **prompt** — The prompt is the user's input text, available as `userPrompt`
  in a flow body.
- **stage** — A stage, `stage(name)(body)`, is a unit of work that commits on
  completion and is skipped on resume. See [Stages](../authoring/stages.md).
- **plan** — A plan, `orca.plan.Plan`, is the task list the planning agent
  (the **planner**) produces. See [Planning](../authoring/planning.md).
- **plan task** — A plan task is one `orca.plan.Task` of a plan. The
  **plan brief** (`Plan.brief`) is the planner's codebase briefing.
- **run** — A run is one prompt's flow execution, across every process it
  takes to finish. See
  [Branches, resume and worktrees](../using/run-lifecycle.md).
- **attempt** — An attempt is one of those processes: one `orca run`, one
  `flow(...)` call.
- **re-run / resume** — A re-run, or resume, is another attempt of an
  unfinished run with the same prompt. It skips the stages already recorded.
- **progress log** — The progress log is `.orca/runs/<key>.progress.json`,
  committed with each stage. It records which stages finished, and their
  results.
- **run target** — The run target is where a run works: a new branch (the
  default), the current branch (`--skip-branch`) or a worktree (`--worktree`).
- **worktree** — A worktree is a second checkout of the repository under
  `.orca/worktrees/`.

## Agents and conversations

- **harness** (also **backend**) — A harness is the coding-agent CLI Orca
  drives: `claude`, `codex`, `opencode`, `pi` or `gemini`.
- **agent** — An agent is a harness with a model and tool settings, such as
  `claude`, `codex.mini` or `codingAgent`. See [Backends](../api/backends.md).
- **role agent** — A role agent is `planningAgent`, `codingAgent` or
  `reviewAgent`, resolved from [settings](../using/settings.md).
- **cheap tier** — The cheap tier, `agent.cheap`, is the harness's cheaper
  model.
- **turn** — A turn is one prompt to an agent and its reply.
- **conversation** — A conversation is the history a harness keeps across the
  turns of one chat or session.
- **one-shot / chat / session** — These are the three ways of talking to an
  agent: `agent.run` is one turn, `agent.chat()` is a conversation for this
  attempt, and `agent.session(name, seed)` is a conversation that survives
  resume. See [Talking to agents](../authoring/talking-to-agents.md).
- **session name / session key** — The session name is the session's role,
  such as `"implementer"`, which `orca continue` matches. The session key is
  the name plus the stage the session is created in.
- **seed / re-seed** — The seed is the context a session starts from, usually
  the plan brief. A session whose conversation is lost is re-seeded, that is,
  started again from its seed.
- **structured output** — Structured output, `resultAs[O]`, is a reply parsed
  into an `O`, which needs a `JsonData[O]`.

## Review

- **reviewer** — A reviewer is a prompt saying what to look for, paired with a
  read-only agent.
- **reviewer catalog** — The reviewer catalog, `reviewerCatalog`, is every
  reviewer a run can use. See [Custom reviewers](../using/reviewers.md).
- **roster** — The roster is the set of reviewers one review call is given.
- **reviewer picker** — The reviewer picker is the cheap agent that chooses,
  from the roster, which reviewers run for a task.
- **review round** — A review round is one pass of the picked reviewers, the
  lint gate and any checks over the change. See
  [Review and fix loops](../authoring/review.md).
- **fix turn** — A fix turn is the coder session's `.run` that fixes a round's
  findings. `maxFixTurns` caps how many there are.
- **finding** — A finding is a problem a reviewer, the lint gate or a check
  reported.
- **declined finding** — A declined finding is a finding the fixer refused,
  with a reason.
- **open finding** — An open finding is a finding the review ended without
  resolving.
- **`OpenFindings`** — `OpenFindings` is what a review returns: the findings it
  left open. See [Data structures](../api/data-structures.md#review).
- **gate** — A gate is a stack command: `format`, `lint` or `test`. The lint
  gate runs each review round.
- **stack settings** — The stack settings are the project's gate commands,
  from `.orca/settings.properties`.
- **`Configured`** — `Configured` is how a review call takes a gate: from
  settings (the default), off, or a given value. See
  [Data structures](../api/data-structures.md#settings).

## Capabilities and tool limits

- **capability** — A capability is a compile-checked token a call needs.
  `InStage` (for agent calls) and `WorkspaceWrite` (for git, `gh` and file
  writes) come from a `stage(...)` body; `FlowControl` (for starting stages and
  creating sessions) comes from the `flow(...)` body. `FlowContext`, which
  grants reads, is not a capability. See
  [Capabilities](../authoring/capabilities.md).
- **fork** — A fork is a function running in parallel under
  `Par.mapUnordered`. See [Stages](../authoring/stages.md#parallel-work).
- **`ToolSet`** — A `ToolSet` says which tools an agent has: `ReadOnly`,
  `NetworkOnly`, `Full` (the default) or `NoTools`. **Enforcement** is how
  strictly each harness holds that limit. See
  [Choosing agents](../authoring/choosing-agents.md).
