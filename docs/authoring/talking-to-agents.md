# Talking to agents

There are three ways to talk to an agent. Which one to pick depends on what the
conversation must survive and who steers it. All of them need to be called
inside a `stage(...)` body, see [Stages](stages.md); that page also defines the
flow thread and forks.

- **`agent.run(prompt)` / `agent.resultAs[O]...run(input)`** asks a one-shot
  question.
- **`agent.chat()`** keeps a conversation going for follow-ups within this
  attempt, including inside forks. Each fork creates its own chat.
- **`agent.session(name, seed)`**, on the flow thread, is for work that edits
  the tree and must pick up after a crash.
- **`session.chat`** continues a durable conversation from a fork, once the
  session has run on the flow thread.

There are two modes. **`.autonomous`** runs the turn unattended, while
**`.interactive`** lets the agent ask you questions in the terminal. Use
`resultAs[O].interactive` on an agent or a chat when a human steers the turn.
Note that `session.run` has no interactive mode: a turn that a human steered
cannot be rebuilt from a seed on resume. To steer a session, use `session.chat`
after it has run.

```{warning}
Interactive turns share your terminal, so never run them in parallel.
```

## Durable sessions

`agent.session(name, seed)` returns the session with the given `name` in the
current stage, creating it on the first call. The `FlowSession` handle survives
crash and resume: the same key resumes the same session, with a warning if this
call's seed differs from the recorded one.

- `name` is the role, for example `"implementer"`, and it is what
  [`orca continue <name>`](../using/shell.md) matches.
- The stage half of the key is implicit. A per-task loop that creates
  `implementer` inside each task's stage gets one session per task, with
  nothing to name by hand. Sessions created in different stages are always
  different sessions. Creating one name twice in one stage is an error: give
  each its own stage, or rename one of them.
- If you rename the stage, the key moves with it. For example, a re-plan that
  rewords a task gives it a fresh session rather than resuming the old
  wording's conversation.
- Create the handle inside the stage that uses it. If several stages share one
  session, create it outside all of them. A handle cannot be a stage result,
  since `FlowSession` has no `JsonData`. Both creating and running happen on
  the flow thread.
- The record behind the handle lives in `.orca/cache/`, not in branch history.
  This means that the stage that created it can fail, and its retry still
  resumes the same conversation.

```scala
val session = codingAgent.session("implementer", seed = plan.brief)
session.run(task.description)
```

### Seeds

The `seed` is the context needed to rebuild the agent: typically the plan
brief, or the issue body when there is no brief. A fresh session is primed with
the seed on first use. If the harness lost the conversation on resume, the
session is re-seeded, with a warning; the history is gone, and the new
conversation gets the seed and a list of the stages already completed. If the
harness still holds the conversation, the session continues with its full
history and is told once that the working tree only has what earlier stages
committed. A conversation continued through `session.chat` is not told this.

### How long a session should live

Every turn sends the whole conversation to the model again, so a session's
cost grows with everything it has done. For this reason, scope a session to a
unit of work, such as a task or a review stage, not to the whole run. The
shipped flows create a session per task, and another one for the final review.

### Harness swaps

If a settings edit changes a role's harness between attempts, a session
recorded under the old harness is not resumed against the new one. Instead,
Orca creates a fresh session from the seed and warns.

## Ephemeral chats

`agent.chat()` returns a `Chat`, which continues one conversation across
`.run` calls within this attempt only: there is no seeding and no persistence.
Chats work inside `Par.mapUnordered`. A typical use is parallel reviewers, each
with its own multi-turn conversation:

```scala
val chats = Par.mapUnordered(4)(reviewers): r =>
  val c = r.chat()
  c.run(s"review the diff: $diff")
  c                       // keep the conversation for a later re-review turn
```

`chat.withAgent(f)` continues the same conversation on a variant of the chat's
agent (`_.withReadOnly`, `_.cheap`, `_.withName("…")`). This is useful for
turns that need other tools, a cheaper model or their own cost line. The
variant must be built from the chat's agent; another harness is refused.

`session.chat` exposes a durable session's conversation as an ephemeral chat.
Only one such chat can be open per session at a time. It is refused while the
harness does not hold the conversation, that is before the session's first
run, or after it was lost on resume.

## Structured output

`resultAs[O]` defines the shape of the reply. `O` needs a `JsonData[O]`
instance, which is used for schema generation and parsing; `derives JsonData`
on a case class provides one. A parameterless enum that derives `JsonData`
travels as its case name, and the schema lists every name. A sum type whose
cases carry fields cannot be an `O`.

For example, to ask a yes/no question with a reason attached:

```scala
case class MergeCheck(ok: Boolean, reason: String) derives JsonData

val check = reviewAgent.resultAs[MergeCheck].autonomous.run("Is this change safe to merge?")
```

If you define an `Announce[O]` instance, the event log prints a friendly
summary instead of raw JSON; the library's `Plan` has one.

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
