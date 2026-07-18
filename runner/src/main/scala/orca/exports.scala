package orca

// Re-exports the user-facing surface from each sub-package so flow scripts can
// pull everything in with a single `import orca.{*, given}`. Mirrors the public
// API the README documents; deliberately omits customisation-only knobs (e.g.
// `orca.plan.PlanPrompts.Planning`) so they stay self-documenting at the call
// site. The flow DSL, StackSettings and Configured live at top-level `orca`
// already, so they need no re-export.

// Usage is carried by OrcaEvent.TokensUsed, so listeners matching it need it in
// scope; CostTracker is instantiable directly by callers via `extraListeners`.
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
  AutonomousAgentCall,
  InteractiveAgentCall,
  Chat,
  ChatCall,
  AgentConfig,
  AutoApprove,
  ToolSet,
  BackendTag,
  Model,
  SessionId,
  JsonData,
  Announce,
  schemaFromJsonData,
  codecFromJsonData
}
export orca.plan.{BugReportMatch, Plan, Sessioned, Task, Title, Triage, Verdict}
// PrSummary is the result type of openPrFromBranch and summarisePr;
// orcaCommentMarker is the idempotency marker gh.upsertComment keys on.
export orca.pr.{openPrFromBranch, orcaCommentMarker, summarisePr, PrSummary}
// Reviewer-customisation surface: compose your own `List[Reviewer]` and
// `buildReviewers` it into the agents `reviewAndFixLoop` takes. IgnoredIssue(s)
// is the result type of fixLoop/reviewAndFixLoop; Lint is constructed at the
// call site for reviewAndFixLoop's `lint` parameter.
export orca.review.{
  allReviewers,
  buildReviewers,
  fixLoop,
  lint,
  minimalReviewers,
  reviewAndFixLoop,
  FixOutcome,
  IgnoredIssue,
  IgnoredIssues,
  Lint,
  Reviewer,
  ReviewerPrompts,
  ReviewBatch,
  ReviewerSelector,
  ReviewIssue,
  ReviewResult,
  RosterEntry
}
// PushFailure is the Left of GitTool.push's Either; BuildWaitFailed the same
// for GitHubTool.waitForBuild.
export orca.tools.{
  BuildOutcome,
  BuildStatus,
  BuildWaitFailed,
  Comment,
  Issue,
  IssueHandle,
  PrHandle,
  PushFailure
}
export orca.tools.opencode.OpencodeLauncher
// Agent-override surface: the wiring an override factory receives, plus each
// backend's default-agent factory (`ClaudeAgents.default(w).opus`, …).
export orca.backend.AgentWiring
export orca.tools.claude.ClaudeAgents
export orca.tools.codex.CodexAgents
export orca.tools.gemini.GeminiAgents
export orca.tools.pi.PiAgents
export orca.tools.opencode.OpencodeAgents
export ox.either.orThrow
