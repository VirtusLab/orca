# Planning

`Plan` (available through `import orca.{*, given}`) holds the planning entry
points. Each call has the form `Plan.<mode>.<operation>`, where the mode is
either `autonomous` or `interactive`, and every operation works in both modes.
Pass `planningAgent` as the `agent`.

In the `autonomous` mode, the planner works with
[`NetworkOnly`](choosing-agents.md#tool-sets) tools and no human is involved.
In the `interactive` mode, the agent can ask the user questions.

The operations are:

- `from(userPrompt, agent, instructions?)` returns a `Plan`. Autonomously, it
  plans in one turn; interactively, you drive the planner conversationally.
- `assessThenPlan(userPrompt, agent, instructions?)` returns a `Verdict[Plan]`.
  It first assesses the prompt, then either continues with `Proceed(plan)` or
  returns a `Rejection`. Interactively, it can ask the user to clarify instead
  of rejecting.
- `triage(report, agent, instructions?)` returns a `Triage`, which classifies
  a bug report as not a bug, untestable, or testable. Interactively, it can ask
  clarifying questions.

The `instructions` argument is optional and replaces the helper's prompt, see
[Customising prompts](extending.md#customising-prompts).

## The plan

A plan is a `Plan(epicId, description, tasks, brief)`, where:

- `tasks` is the list of `Task(title: Title, description: String)` to
  implement, in order. `Title` wraps a short label.
- `brief` is a concise briefing on the codebase. Feed it to the implementer
  session as its seed; `plan.taskPrompt(task)` prepends the brief to a task's
  description.
- `epicId` is a kebab-case identifier for the plan. It is not the branch name:
  the run names its branch separately.

## `WithChat`

Every operation returns a `WithChat[<result>]`: the result together with the
`Chat` that produced it (see
[Ephemeral chats](talking-to-agents.md#ephemeral-chats)). With it, you can:

- keep talking to the planner using `chat.run(...)`. Note that these later
  turns have the `Full` tool set, not the planner's read-only one.
- take `.value` and seed an implementer session with `plan.brief`.

The chat is lost on resume, which is why the shipped flows take `.value` right
away. When you want both, destructure:

```scala
val WithChat(chat, plan) = Plan.autonomous.from(userPrompt, planningAgent)
```

## Reviewing the plan

Given a `WithChat[Plan]`, `.reviewed()` refines the plan before implementing
it. A critic in a fresh conversation, which has not seen the planner's
exploration, checks the plan against the request and the code. The planner then
weighs the critique and returns an improved `Plan`. Both turns are read-only. It
chains naturally:

```scala
val plan = Plan.autonomous.from(userPrompt, planningAgent).reviewed().value
```

`.reviewed(variant = _.cheap)` runs both turns on a variant of the planner's
agent, for example its cheap model.

## Verdicts and triage

`assessThenPlan` returns a `Verdict`: either `Verdict.Proceed(plan)`, meaning
the plan should be implemented, or `Verdict.Rejection(kind, body)`, where
`kind` says whether the rejection is a question, a critique or a refusal. The
flow shows a rejection to whoever asked, for example as an issue comment;
`flows/issue-pr.sc` does this.

`triage` returns a `Triage` sum type to pattern-match on: the cases are
`NotABug`, `Untestable` and `Testable`, each carrying its own fields.
`flows/issue-pr-bugfix.sc` uses it to decide between a comment and a
reproduction test. The same flow asks the agent for a `BugReportMatch` to check
that a CI failure matches the report.
