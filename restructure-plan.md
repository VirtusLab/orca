# Module Restructure Plan

Goal: split the current `core` module into two, rename `cli` → `runner`, and
push each backend-specific implementation back into its backend module so
packages end up self-contained.

## Target layout

```
orca/
├── tools/      # Tool interfaces + implementations + supporting infrastructure
├── flow/       # Flow helpers (stage, fail, fixLoop, reviewAndFix, lint) + review types
├── claude/     # Claude backend — now includes DefaultClaudeTool + DefaultLlmCall
├── codex/      # Codex backend — skeleton, unchanged
└── runner/     # Entry point + wiring, with a terminal sub-package
```

## Module dependency graph

```
tools   (standalone)
  ├── flow       → tools
  ├── claude     → tools
  ├── codex      → tools
  └── runner     → tools + flow + claude + codex
```

The terminal UI stays inside `runner` so swapping it out (e.g. for
`SlackInteraction`) is a matter of substituting one dependency, not rewiring
modules.

## Package organization

Users still do `import orca.*` for the user-facing surface. That package spans
multiple modules because several modules contribute members to it; Scala
allows this and it keeps the consumer API flat.

| Package | Module | Content |
|---|---|---|
| `orca` | tools + flow + runner | User-facing traits, types, accessors, flow helpers, `orca()` entry |
| `orca.subprocess` | tools | `CliRunner`, `OsProcCliRunner`, `StubCliRunner` |
| `orca.io` | tools | `JsonSchemaGen`, `ResponseParser`, `DoneMarkerExtractor` |
| `orca.tools` | tools | `OsFsTool`, `OsGitTool`, `OsGitHubTool` |
| `orca.claude` | claude | Claude backend, `DefaultClaudeTool`, `DefaultLlmCall` |
| `orca.codex` | codex | Codex backend (skeleton) |
| `orca.runner` | runner | `DefaultFlowContext`, wiring internals |
| `orca.runner.terminal` | runner | `TerminalInteraction`, `OrcaSpinner`, `OrcaArgs` |

## Content partition

**tools** (mostly what `core` has today):
- All tool interfaces: `FsTool`, `GitTool`, `GitHubTool`, `LlmTool`,
  `ClaudeTool`, `CodexTool`, `LlmCall`, `LlmBackend`, `InteractiveHandle`
- All OS-backed implementations: `OsFsTool`, `OsGitTool`, `OsGitHubTool`,
  `OsProcCliRunner` (+ stubs)
- Structured I/O: `JsonSchemaGen`, `ResponseParser`, `DoneMarkerExtractor`,
  `DefaultPromptTemplate` + `PromptTemplate` trait
- Shared types: `Backend`, `SessionId`, `LlmConfig`, `AutoApprove`,
  `UnapprovedPolicy`, `Usage`, `LlmResult`, `Comment`, `CommitInfo`,
  `PrHandle`, `BuildStatus`, `BuildOutcome`, `OrcaFlowException`
- Event infrastructure: `OrcaEvent`, `OrcaListener`, `Interaction` (the
  trait itself is in tools; the dispatcher is in flow)
- `AgentInput` trait + given instances
- Re-exports: `Schema`, `ConfiguredJsonValueCodec`

**flow** (new):
- `FlowContext` trait
- `stage`, `fail`, `fixLoop`, `reviewAndFix`, `lint`
- `CostTracker`, `EventDispatcher`
- Review types: `ReviewIssue`, `Severity`, `ReviewResult`, `IgnoredIssue`,
  `IgnoredIssues`, `ReviewContext`, `SelectedReviewers`
- `defaultReviewers`, `ReviewerPrompts`, `NamedLlmTool`
- Top-level accessors: `claude`, `git`, `gh`, `fs`, `codex`, `userPrompt`

**claude** (additions to current claude module):
- `DefaultClaudeTool` (moved from `cli`)
- `DefaultLlmCall` (moved from `cli`)

**runner** (renamed from `cli`):
- `orca()` entry function in `package orca`
- `DefaultFlowContext` in `package orca.runner`
- Terminal layer in `package orca.runner.terminal`: `TerminalInteraction`,
  `OrcaSpinner`, `OrcaArgs`, and the CLI `Main`

## Steps

Each step ends with a compile + test + review cycle and its own commit.

1. **Create `flow` module.** Stand up the new module, move every helper file
   listed above, update dependent modules' imports, keep all 119 tests green.
2. **Rename `core` → `tools`.** Directory rename + `build.sbt` rename + update
   `dependsOn` references. No package changes.
3. **Move Claude user-facing wiring into `claude`.** Relocate
   `DefaultClaudeTool` and `DefaultLlmCall` from the old `cli` module into
   `orca.claude`. `runner` stops needing the Claude-specific wiring directly.
4. **Rename `cli` → `runner`.** Split its contents: `orca()` in `package
   orca`, `DefaultFlowContext` in `orca.runner`, terminal layer
   (`TerminalInteraction`, `OrcaSpinner`, `OrcaArgs`, `Main`) in
   `orca.runner.terminal`.

Review after each step, fix any flagged issues, then commit. README and
`plan.md` get touched up at the end so the module layout stays accurate.
