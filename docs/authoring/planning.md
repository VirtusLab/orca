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
- `roadmap(userPrompt, agent, instructions?)` returns a `Roadmap`, which
  splits a change too large for one plan into ordered epics. See
  [Roadmaps](#roadmaps).
- `triage(request, agent, instructions?)` returns a `Triage`, which rejects a
  request or accepts it as a bug or a change. See [Triage](#triage).
  Interactively, it can ask clarifying questions.

The `instructions` argument is optional and replaces the helper's prompt, see
[Customising prompts](extending.md#customising-prompts).

## The plan

A plan is a `Plan(id, description, tasks, brief)`, where:

- `tasks` is the list of `Task(title: Title, description: String)` to
  implement, in order. `Title` wraps a short label.
- `brief` is a concise briefing on the codebase. Feed it to the implementer
  session as its seed; `plan.taskPrompt(task)` prepends the brief to a task's
  description.
- `id` is a kebab-case identifier for the plan. It is not the branch name:
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

## Roadmaps

A roadmap is a `Roadmap(description, epics, brief)`, where each
`Epic(title: Title, goal: String)` is planned into tasks only when its turn
comes: `roadmap.epicPrompt(epic)` is the planning input for `from`, naming the
epics already done. `.reviewed()` works on a `WithChat[Roadmap]` as on a plan.

## Triage

`triage` checks a request (a bug report, a feature request or any other ask)
against the repository and returns a `Triage`:

- `Triage.Reject(reply)`: the request should not be done; `reply` is the
  answer to whoever asked.
- `Triage.Accept(summary, brief, kind)`: the work should be done. `brief` is
  what triage verified, for seeding the planner. `kind` is
  `TestableBug(failingTestPath)`, `UntestableBug(reproductionSteps)` or
  `Change`.

`flows/resolve.sc` uses it to reject, reproduce or go straight to planning.
For a testable bug, it calls `reproduceBug(request, testPath, agent)` inside a
stage: the agent writes the failing test, and a separate turn returns a
`BugReportMatch` saying whether the failure is the one the request describes.
After a second mismatch the stage fails.
