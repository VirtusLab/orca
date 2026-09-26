# Planning

`Plan` (from `import orca.{*, given}`) has the planning entry points. Each
call is `Plan.<mode>.<operation>`. Mode is `autonomous` or `interactive`. Every
operation works in both modes. Pass `planningAgent` as the `agent`.

| Operation | Result | `autonomous` ([`NetworkOnly`](choosing-agents.md#tool-sets) tools, no human) | `interactive` (the agent can ask the user questions) |
|---|---|---|---|
| `from(userPrompt, agent, instructions?)` | `Plan` | plan in one turn | drive the planner conversationally |
| `assessThenPlan(userPrompt, agent, instructions?)` | `Verdict[Plan]` | assess, then `Proceed(plan)` or `Rejection` | same, but can ask the user to clarify instead of rejecting |
| `triage(report, agent, instructions?)` | `Triage` | classify a bug report: not a bug, untestable, or testable | same, with clarifying questions |

`instructions` is optional and replaces the helper's prompt, see
[Customising prompts](extending.md#customising-prompts).

## The plan

A `Plan(epicId, description, tasks, brief)`:

- `tasks` is the list of `Task(title: Title, description: String)` to
  implement, in order. `Title` wraps a short label.
- `brief` is a concise codebase briefing. Feed it to the implementer session as
  its seed. `plan.taskPrompt(task)` prepends the brief to a task's description.
- `epicId` is a kebab-case identifier for the plan, not the branch name. The
  run names its branch separately.

## `WithChat`

Every cell returns `WithChat[<result>]`: the result and the `Chat` that
produced it (see [Ephemeral chats](talking-to-agents.md#ephemeral-chats)). You
can:

- keep talking to the planner: `chat.run(...)`. These later turns have the
  `Full` tool set, not the planner's read-only one.
- take `.value` and seed an implementer session with `plan.brief`.

The chat is lost on resume, so the shipped flows take `.value`. Destructure
when you want both:

```scala
val WithChat(chat, plan) = Plan.autonomous.from(userPrompt, planningAgent)
```

## Reviewing the plan

From a `WithChat[Plan]`, `.reviewed()` refines the plan before implementing.
The planner reviews its own draft with read-only tools and returns an improved
`Plan`. Chain it:

```scala
val plan = Plan.autonomous.from(userPrompt, planningAgent).reviewed().value
```

`.reviewed(variant = _.cheap)` runs the review turn on a variant of the
planner's agent, for example its cheap model.

## Verdicts and triage

`assessThenPlan` returns a `Verdict`: `Verdict.Proceed(plan)` to implement, or
`Verdict.Rejection(kind, body)`. `kind` says whether it is a question, a
critique or a refusal. The flow shows it to whoever asked, for example as an
issue comment. `flows/issue-pr.sc` does this.

`triage` returns a `Triage` sum type to pattern-match: `NotABug`, `Untestable`
or `Testable`; each case carries its own fields. `flows/issue-pr-bugfix.sc`
uses it to decide between a comment and a reproduction test. The same flow
asks the agent for a `BugReportMatch` to check that a CI failure matches the
report.
