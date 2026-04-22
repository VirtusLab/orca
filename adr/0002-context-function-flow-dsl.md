# 0002. Flow scripts use a context-function DSL with an ambient FlowContext

Status: Accepted · Date: 2026-04-22

## Decision

The user writes flow scripts inside an `orca:` block:

```scala
orca:
  val plan = claude.result[Plan].prompt(userPrompt)
  git.createBranch(plan.branchName)
  ...
```

`orca()` takes a `FlowContext ?=> Unit` — a Scala 3 context function —
and top-level `def claude(using FlowContext): ClaudeTool`, `def
git(using FlowContext): GitTool`, `def userPrompt(using FlowContext):
String`, etc. resolve the ambient context implicitly.

## Why

- Users don't pass `ctx` to every helper call; the ergonomics match a
  script, not a service.
- Swapping the entire runtime (Slack instead of terminal, custom git,
  mocked Claude) is `orca(interaction = ..., git = ..., claude = ...)`
  — no wrapping, no subclassing.
- The compiler enforces that helpers like `stage`, `fail`, `fixLoop` can
  only be called where a `FlowContext` is in scope.

## Consequences

- Flow helpers are top-level `def`s taking `(using FlowContext)`, not
  methods on a base class.
- Testing a flow requires a `TestFlowContext` that stubs tools
  lazily — we ship one for library tests.
- `import orca.*` pulls in the entry function, the accessors, the
  helpers, and the data types in a single line.
