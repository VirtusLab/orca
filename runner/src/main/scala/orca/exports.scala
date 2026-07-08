package orca

// Re-exports the user-facing surface from each sub-package so flow scripts
// can pull everything in with a single `import orca.{*, given}`. The list
// mirrors what the README documents as the public API; deliberately omits
// customisation-only knobs (e.g. `orca.plan.PlanPrompts.Planning`) so they
// stay self-documenting at the call site rather than fading into the
// wildcard.

// flow DSL (flow, stage, fail, accessors, OrcaArgs, FlowContext) lives at
// top-level `orca` so its symbols sit at the heart of the user surface; no
// re-export needed.

// Usage/Cost/CostTracker: OrcaEvent.TokensUsed carries a Usage, so any
// listener pattern-matching it needs Usage in scope; CostTracker is the type
// flow's own scaladoc invites callers to instantiate directly (`new
// CostTracker(pricing)`, passed via `extraListeners`), and its accessors
// report in Cost.
export orca.events.{
  OrcaEvent,
  OrcaListener,
  Pricing,
  PriceList,
  ModelPricing,
  Usage,
  Cost,
  CostTracker
}
export orca.agents.{
  Agent,
  ClaudeAgent,
  CodexAgent,
  OpencodeAgent,
  PiAgent,
  GeminiAgent,
  AgentCall,
  AutonomousTextCall,
  AutonomousAgentCall,
  InteractiveAgentCall,
  AgentConfig,
  AutoApprove,
  ToolSet,
  BackendTag,
  CanAskUser,
  Model,
  SessionId,
  JsonData,
  Announce,
  schemaFromJsonData,
  codecFromJsonData
}
export orca.plan.{BugReportMatch, Plan, Sessioned, Task, Title, Triage, Verdict}
export orca.pr.{summarisePr, PrSummary}
// IgnoredIssue(s): the return type of both fixLoop and reviewAndFixLoop above
// — a caller binding the result to a typed val, or inspecting `.issues`,
// needs it in scope.
export orca.review.{
  allReviewers,
  fixLoop,
  lint,
  minimalReviewers,
  reviewAndFixLoop,
  FixOutcome,
  IgnoredIssue,
  IgnoredIssues,
  ReviewBatch,
  ReviewerSelector,
  ReviewIssue,
  ReviewResult,
  RosterEntry
}
// PushFailure: the Left of GitTool.push's Either — pattern-matching its
// NonFastForward/RemoteDeclined cases needs it in scope.
export orca.tools.{
  BuildOutcome,
  BuildStatus,
  Comment,
  Issue,
  IssueHandle,
  PrHandle,
  PushFailure
}
export orca.tools.opencode.OpencodeLauncher
// Agent-override surface: the wiring an override factory receives, plus each
// backend's public default-agent factory (`ClaudeAgents.default(w).opus`, …).
export orca.backend.AgentWiring
export orca.tools.claude.ClaudeAgents
export orca.tools.codex.CodexAgents
export orca.tools.gemini.GeminiAgents
export orca.tools.pi.PiAgents
export orca.tools.opencode.OpencodeAgents
export ox.either.orThrow
