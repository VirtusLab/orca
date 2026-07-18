package orca

// Re-exports the user-facing surface from each sub-package so flow scripts
// can pull everything in with a single `import orca.{*, given}`. The list
// mirrors what the README documents as the public API; deliberately omits
// customisation-only knobs (e.g. `orca.plan.PlanPrompts.Planning`) so they
// stay self-documenting at the call site rather than fading into the
// wildcard.

// flow DSL (flow, stage, fail, accessors, OrcaArgs, FlowContext),
// StackSettings, and Configured live at top-level `orca` so their symbols sit
// at the heart of the user surface; no re-export needed (re-exporting a
// top-level `orca` symbol from this `package orca` file would be
// self-referential).

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
// openPrFromBranch bundles the push→summarise→createPr tail every PR flow
// otherwise hand-rolls; PrSummary is its (and summarisePr's) result type.
// orcaCommentMarker builds the idempotency marker gh.upsertComment keys on.
export orca.pr.{openPrFromBranch, orcaCommentMarker, summarisePr, PrSummary}
// IgnoredIssue(s): the return type of both fixLoop and reviewAndFixLoop above
// — a caller binding the result to a typed val, or inspecting `.issues`,
// needs it in scope.
// Reviewer + ReviewerPrompts + buildReviewers: the reviewer-customisation
// surface — compose your own `List[Reviewer]` (shipped `ReviewerPrompts`
// entries, a subset, and/or your own) and `buildReviewers` it into the agents
// `reviewAndFixLoop` takes, to swap or extend what `allReviewers` builds.
// Lint: `reviewAndFixLoop`'s `lint: Configured[Lint]` parameter is a public
// type — a caller constructs `Lint(command, agent)` at the call site, so it
// must resolve through the same wildcard import as the loop itself.
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
// PushFailure: the Left of GitTool.push's Either — pattern-matching its
// NonFastForward/RemoteDeclined cases needs it in scope. BuildWaitFailed is
// the same shape for GitHubTool.waitForBuild's Either.
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
// backend's public default-agent factory (`ClaudeAgents.default(w).opus`, …).
export orca.backend.AgentWiring
export orca.tools.claude.ClaudeAgents
export orca.tools.codex.CodexAgents
export orca.tools.gemini.GeminiAgents
export orca.tools.pi.PiAgents
export orca.tools.opencode.OpencodeAgents
export ox.either.orThrow
