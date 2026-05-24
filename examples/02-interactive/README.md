# Example 02 — interactive planning

Same in-memory shape as [01-simple](../01-simple/), but the planner is
allowed to ask the user clarifying questions before producing the plan.
Use this when the prompt is open-ended enough that the agent would
otherwise guess wrong.

## What changes vs 01

One line in the flow:

```scala
// 01-simple: Plan.autonomous.from(userPrompt, claude)
Plan.interactive.from(userPrompt, claude)
```

`Plan.interactive.from` wires an `ask_user` MCP tool into the planner's
session. When the agent decides it needs information from you (and only
then — the system prompt nudges it to make reasonable assumptions
otherwise) the conversation pauses, your terminal shows the question,
and your typed answer becomes the tool result. The agent continues
planning with the new context.

## Why the example prompt is vague

The seeded calculator crate has `add` and `subtract`. The recommended
prompt is:

```
Add a new arithmetic operation to the calculator crate. Ask the user which.
```

The first sentence is deliberately under-specified; the trailing
instruction tells the planner to call `ask_user` rather than guessing
the operation. The agent should pause and ask which one (multiply,
divide, modulo, power, …) before drafting tasks.

## Prerequisites

- JDK 21+, [scala-cli](https://scala-cli.virtuslab.org/).
- `claude` logged in (see the repo root README).
- `cargo` on PATH — same Rust calculator starter as 01-simple.

## Run

The seed script prints the exact `scala-cli run` line:

```bash
./examples/02-interactive/create-test-project.sh
# → "Test project ready at: /tmp/orca-02-interactive-…"

cd /tmp/orca-02-interactive-…
scala-cli run implement-interactive.sc -- "Add a new arithmetic operation to the calculator crate. Ask the user which."
```

Read the question at the `?` prompt and type your answer. The planner
may ask more than once before it has enough to plan.
