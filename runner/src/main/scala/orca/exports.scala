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

export orca.events.{OrcaEvent, OrcaListener, Pricing, PriceList, ModelPricing}
export orca.llm.{
  LlmTool,
  ClaudeTool,
  CodexTool,
  OpencodeTool,
  PiTool,
  GeminiTool,
  LlmCall,
  AutonomousTextCall,
  AutonomousLlmCall,
  InteractiveLlmCall,
  LlmConfig,
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
export orca.plan.{
  BugReportMatch,
  Plan,
  PlanLike,
  PlanWithBrief,
  Sessioned,
  Task,
  Title,
  Triage,
  Verdict
}
export orca.pr.{summarisePr, PrSummary}
export orca.review.{
  allReviewers,
  fixLoop,
  lint,
  minimalReviewers,
  reviewAndFixLoop,
  FixOutcome,
  ReviewerSelector,
  ReviewIssue,
  ReviewResult
}
export orca.tools.{
  BuildOutcome,
  BuildStatus,
  Comment,
  Issue,
  IssueHandle,
  PrHandle
}
export orca.tools.opencode.OpencodeLauncher
export ox.either.orThrow
