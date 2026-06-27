# 0002. Flow scripts use a context-function DSL with an ambient FlowContext

Status: Accepted · Date: 2026-04-22 (updated 2026-04-23)

## Decision

The user writes flow scripts inside a `flow(args)` block:

```scala
flow(OrcaArgs.from(args.toSeq)):
  val plan = claude.resultAs[Plan].autonomous(userPrompt)
  git.createBranch(plan.branchName)
  ...
```

`flow` takes the parsed CLI args first (required, so `userPrompt` etc.
resolve against the command line rather than silently defaulting to
empty), any number of named overrides (`git = ...`, `interaction = ...`,
etc.), and the body as a second parameter list typed
`FlowContext ?=> Unit`. Top-level `def claude(using FlowContext):
ClaudeAgent`, `def git(using FlowContext): GitTool`, `def
userPrompt(using FlowContext): String`, etc. resolve the ambient
context implicitly.

## Why

- Users don't pass `ctx` to every helper call; the ergonomics match a
  script, not a service.
- Swapping the entire runtime (Slack instead of terminal, custom git,
  mocked Claude) is one named argument on the same `flow(...)` call —
  no wrapping, no subclassing.
- The compiler enforces that helpers like `stage`, `fail`, `fixLoop`
  can only be called where a `FlowContext` is in scope.
- Requiring `args` up-front avoids a silent footgun: the previous
  bare `flow:` made `userPrompt` always empty because nothing parsed
  the argv; real scripts picked that up at runtime against a live
  Claude session.

## Consequences

- Flow helpers are top-level `def`s taking `(using FlowContext)`, not
  methods on a base class.
- Testing a flow requires a `TestFlowContext` that stubs tools lazily
  — we ship one for library tests.
- `import orca.{*, given}` pulls in the entry function, the accessors,
  the helpers, the data types, and the JsonData forwarder givens in a
  single line. The `given` selector is required because Scala 3
  excludes given instances from plain wildcard imports, and the
  forwarders bridge `derives JsonData` to the underlying Schema/codec
  needed during nested macro derivation.
- The two-parameter-list shape (`flow(args, ...)(body)`) keeps Scala
  3's `flow(...): body` fewer-braces working — the block lands in its
  own argument list, which lets the `FlowContext ?=>` given propagate
  into the body. A single-list shape with body as a defaulted
  positional parameter silently loses the given.
