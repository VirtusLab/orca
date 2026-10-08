# Extending flows in code

Most of what `flow(...)` builds by default can be replaced from the script.
There are two exceptions. `git` cannot be replaced, because the runtime owns
the run's branch and commits through it. The harness SPI cannot be replaced
either; a new harness is added as an Orca module.

## `flow(...)` parameters

The full signature of `flow(...)` is:

```scala
flow(
  args: OrcaArgs,
  workDir?, interaction?, extraListeners?,
  branchNaming?, stackSettings?,
  planningAgent?, codingAgent?, reviewAgent?,
  claude?, codex?, opencode?, pi?, gemini?,
  gh?, fs?, prompts?, pricing?
)(body)
```

The parameters are:

- `args`: the parsed command line. A script can change it before passing it
  on; for example `OrcaArgs(args).copy(target = RunTarget.Worktree)` overrides
  the flags, see
  [Branches, resume and worktrees](../using/run-lifecycle.md#run-targets).
- `workDir`: the repository to run in; defaults to the current directory.
- `interaction`: your own `orca.backend.Interaction`, the object that asks the
  user questions and shows approval requests, for example over Slack instead
  of the terminal. It is not exported from `orca.*`, so import it by its full
  path.
- `extraListeners`: additional `OrcaListener`s, which receive the run's events
  (stages, agent turns, tool calls).
- `branchNaming`: `Some(BranchNamingStrategy.issue(handle))`, with an
  `IssueHandle`, names the branch after an issue instead of after a label that
  a cheap model derives. `--branch <name>` still overrides it.
- `stackSettings`: `Some(StackSettings(...))` pins the format, lint and test
  commands. The stack keys in the settings file are then ignored and never
  written; the agent keys are still used.
- `planningAgent`, `codingAgent`, `reviewAgent`: per-role agent overrides, for
  example `Some(_.claude.opus)`. They take precedence over the project and
  global [settings](../using/settings.md).
- `claude`, `codex`, `opencode`, `pi`, `gemini`: agent factories, see
  [Replacing an agent](#replacing-an-agent) below.
- `gh`, `fs`: your own tool implementations, for example `gh = Some(myGh)`.
- `prompts`: the per-call prompt wrappers (autonomous, interactive, retry), as
  one set.
- `pricing`: the price table that the cost log and the closing summary use.

## Replacing an agent

Each agent slot takes a factory rather than an agent. The factory receives the
attempt's `AgentWiring` (the event sink, interaction, working directory and
prompts), so that your agent reports events the same way the defaults do. For
example:

```scala
flow(OrcaArgs(args), claude = Some(w => ClaudeAgents.default(w).opus))
```

Factories exist for all five harnesses: `ClaudeAgents.default(w)`,
`CodexAgents.default(w)`, `GeminiAgents.default(w)`, `PiAgents.default(w)` and
`OpencodeAgents.default(w, launcher)`. The accessors they offer are listed in
[Backends](../api/backends.md). Each slot has the type
`AgentWiring => Ox ?=> <Harness>Agent`. The factory runs inside the run's
[Ox](https://ox.softwaremill.com/) scope, so a harness can tie a long-lived
process to it; OpenCode uses this for its shared `opencode serve`.

`OpencodeLauncher.ollama("qwen3-coder")` starts that server via `ollama launch
opencode`, with that one model pinned, see
[Agent CLIs](../using/agent-clis.md).

## Customising prompts

Every helper that sends a prompt to an LLM has an `instructions: String`
parameter with a default. The default is a constant on a `XxxPrompts` object
that lives next to the helper. You can override it entirely, or compose with
the default:

```scala
import orca.plan.{Plan, PlanPrompts}

Plan.interactive.from(
  userPrompt,
  planningAgent,
  instructions = PlanPrompts.Planning + "\n\nPrioritise observability tasks first."
)
```

The prompt objects and the constants they hold are:

- `orca.plan.PlanPrompts`: `Planning`, `Triage`, `Critique`, `Revise`,
  `Roadmap`, `RoadmapCritique`, `RoadmapRevise`, `Reproduce`, `ReproduceRetry`,
  `ReproductionCheck`
- `orca.pr.PrPrompts`: `Summarise`
- `orca.review.ReviewLoopPrompts`: `Fix`, `SelectReviewers`, `SummariseLint`
- `orca.review.ReviewerPrompts`: the per-reviewer system prompts

To retune a reviewer for one project without writing code, add a file under
`.orca/reviewers/` instead, see [Custom reviewers](../using/reviewers.md). The
convention behind these objects is
[ADR 0010](https://github.com/VirtusLab/orca/blob/master/adr/0010-prompts-and-helpers-convention.md).
