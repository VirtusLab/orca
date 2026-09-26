# Talking to agents

There are three ways to talk to an agent. Pick by what the conversation must
survive and who steers it. All of them need a `stage(...)` body, see
[Stages](stages.md); that page also defines the flow thread and forks.

- **`agent.run(prompt)` / `agent.resultAs[O]...run(input)`** for a one-shot
  question.
- **`agent.chat()`** for follow-ups within this attempt, including inside forks.
  Each fork creates its own.
- **`agent.session(name, seed)`**, on the flow thread, for work that edits the
  tree and must pick up after a crash.
- **`session.chat`** to continue a durable conversation from a fork, once the
  session has run on the flow thread.

Two modes: **`.autonomous`** runs the turn unattended; **`.interactive`** lets
the agent ask you questions in the terminal. Use `resultAs[O].interactive` on
an agent or a chat when a human steers the turn. `session.run` has no
interactive mode: a turn a human steered cannot be rebuilt from a seed on
resume. Steer a session through `session.chat` after it has run. Interactive
turns share your terminal: never run them in parallel.

## Durable sessions

`agent.session(name, seed)` returns the session with this `name` in the current
stage, creating it on first call. The `FlowSession` handle survives crash and
resume: the same key resumes the same session, with a warning if this call's
seed differs.

- `name` is the role, `"implementer"`, and what
  [`orca continue <name>`](../using/shell.md) matches.
- The stage half of the key is implicit. A per-task loop that creates
  `implementer` inside each task's stage gets one session per task with
  nothing to name by hand. Sessions created in different stages are always
  different sessions. Creating one name twice in one stage is an error: give
  each its own stage, or rename one.
- Rename the stage and the key moves with it. A re-plan that rewords a task
  gives it a fresh session rather than resuming the old wording's conversation.
- Create the handle inside the stage that uses it. If several stages share one
  session, create it outside all stages. A handle cannot be a stage result:
  `FlowSession` has no `JsonData`. Creating and running happen on the flow
  thread.
- The record behind the handle lives in `.orca/cache/`, not in branch history,
  so the stage that created it can fail and its retry still resumes the same
  conversation.

```scala
val session = codingAgent.session("implementer", seed = plan.brief)
session.run(task.description)
```

### Seeds

The `seed` is the context needed to rebuild the agent: typically the plan brief,
or the issue body when there is no brief. A fresh session is primed with it on
first use. If the harness lost the conversation on resume, the session is
re-seeded, with a warning. The history is gone. The new conversation gets the
seed and a list of the stages already completed. If the harness still holds
the conversation, the session continues with full history and is told once
that the working tree only has what earlier stages committed. A conversation
continued through `session.chat` is not told.

### How long a session should live

Every turn sends the whole conversation to the model again, so a session's
cost grows with everything it has done. Scope one to a unit of work, a task or
a review stage, not to the run. The shipped flows create a session per task and
another for the final review.

### Harness swaps

If a settings edit changes a role's harness between attempts, a session
recorded under the old harness is not resumed against the new one. Orca creates
a fresh session from the seed and warns.

## Ephemeral chats

`agent.chat()` returns a `Chat` continuing one conversation across `.run` calls
within this attempt only: no seeding, no persistence. Chats work inside
`Par.mapUnordered`. Typical use: parallel reviewers, each with its own
multi-turn conversation.

```scala
val chats = Par.mapUnordered(4)(reviewers): r =>
  val c = r.chat()
  c.run(s"review the diff: $diff")
  c                       // keep the conversation for a later re-review turn
```

`chat.withAgent(f)` continues the same conversation on a variant of the chat's
agent (`_.withReadOnly`, `_.cheap`, `_.withName("…")`) for turns that need other
tools, a cheaper model or their own cost line. The variant must be built from
the chat's agent; another harness is refused.

`session.chat` is a durable session's conversation as an ephemeral chat. Only
one such chat can be open per session at a time. It is refused while the
harness does not hold the conversation: before the session's first run, or
after it was lost on resume.

## Structured output

`resultAs[O]` defines the shape of the reply. `O` needs a `JsonData[O]`,
provided by `derives JsonData` on a case class, for schema generation and
parsing. A parameterless enum that derives `JsonData` travels as its case name
and the schema lists every name. A sum type whose cases carry fields cannot be
an `O`.

Define an `Announce[O]` instance to print a friendly summary in the event log
instead of raw JSON; the library's `Plan` has one.

```scala
case class MergeCheck(ok: Boolean, reason: String) derives JsonData

val check = reviewAgent.resultAs[MergeCheck].autonomous.run("Is this change safe to merge?")
```

## Summary

| Call | Conversation | Survives crash/resume | Mode | Output | Needs | In a fork |
|---|---|---|---|---|---|---|
| `agent.run(prompt)` | new, one turn | no | autonomous | text | `InStage` | yes |
| `agent.resultAs[O].{autonomous,interactive}.run(input)` | new, one turn | no | either, as called | `O` | `InStage` | yes* |
| `agent.chat()` → `chat.run(prompt)` / `chat.resultAs[O]....run(input)` | new, then continued by every turn | no | either, as called | text or `O` | `InStage` | yes* |
| `agent.session(name, seed)` → `session.run(prompt)` / `session.resultAs[O].run(input)` | named; resumed, or restarted from the seed if the harness lost it | yes | autonomous | text or `O` | `FlowContext`, `FlowControl`, `InStage`, `WorkspaceWrite` | no |
| `session.chat` → as `Chat` | the session's | no (turns not recorded) | either, as called | text or `O` | `InStage` | yes* |
| `Plan.{autonomous,interactive}.*` → `WithChat`; `.reviewed()`, `.chat` | new planning conversation | no | as named | `O` | `FlowContext`, `InStage` | yes* |
| `reviewAndFixLoop` / `reviewThenFix` | new reviewer chats; continues `coderSession` | the coder session does | autonomous | findings | `FlowContext`, `FlowControl`, `InStage`, `WorkspaceWrite` | no |
| `lint(commands, agent)` | new, or continues a `Lint.summariser` | no | autonomous | `ReviewResult` | `FlowContext`, `InStage` | yes |

\* Interactive turns share your terminal: run them one at a time. The "Needs"
column lists the [capabilities](capabilities.md) each call takes.
