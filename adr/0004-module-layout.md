# 0004. Split the codebase into `tools / flow / backend / runner` modules

Status: Accepted · Date: 2026-04-22

## Decision

```
tools   → standalone   — tool interfaces + os-backed impls + structured I/O + events
flow    → tools        — FlowContext, stage/fail/fixLoop/reviewAndFix/lint, review types
claude  → tools        — Claude backend, DefaultClaudeAgent, DefaultAgentCall
codex   → tools        — Codex backend (future)
runner  → tools+flow+claude+codex  — flow() entry, DefaultFlowContext, terminal layer
```

Implementations live under `orca.tools.<capability>` sub-packages:
`orca.tools.fs` (OsFsTool), `orca.tools.git` (OsGitTool),
`orca.tools.github` (OsGitHubTool), `orca.tools.claude` (ClaudeBackend +
DefaultClaudeAgent), `orca.tools.codex` (future). The top-level `orca`
namespace stays reserved for the user-facing surface (traits, accessors,
`flow`/`flowWith`, `JsonData`, `OrcaArgs`).

`runner` publishes as `com.virtuslab::orca` so a flow script adds exactly
one dependency and receives every other module transitively. The root
aggregate project is called `orca-root`.

## Why

- Consumers shouldn't be forced to pull in `flow` just to use the
  tool interfaces, or pull in a backend just to consume `FlowContext`.
- The tool interfaces don't depend on the flow helpers or on any
  backend, so sits at the bottom of the DAG with no dependencies on
  other Orca modules.
- Swapping the terminal runtime for e.g. Slack is a matter of
  substituting one `Interaction` implementation — the
  `orca.runner.terminal` sub-package is the only place that has to
  change, without touching wiring or tool code.

## Consequences

- Package `orca` (the user-facing surface) spans multiple modules —
  `tools` contributes traits/types, `flow` contributes helpers and
  accessors, `runner` contributes the `flow`/`flowWith` entry points.
  Scala allows this and it keeps `import orca.{*, given}` flat for
  flow-script authors.
- Adding a backend is one new module with `.dependsOn(tools)`;
  `runner.dependsOn(..., newBackend)` picks it up for default wiring.
- Moving code between `tools` and `flow` (or between backend modules)
  changes the dependency graph and typically requires explicit
  imports, which surface as compile errors — the guardrail we want.
