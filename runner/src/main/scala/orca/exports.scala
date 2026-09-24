package orca

// Re-exports the user-facing surface from each sub-package so flow scripts can
// pull everything in with a single `import orca.{*, given}`. Mirrors the public
// API the README documents; deliberately omits customisation-only knobs (e.g.
// `orca.plan.PlanPrompts.Planning`) so they stay self-documenting at the call
// site. The flow DSL, StackSettings and Configured live at top-level `orca`
// already, so they need no re-export. Opaque types are aliased at the bottom of
// this file, never `export`ed.

// Usage, Cost/CostBasis and Announcement are carried by OrcaEvent cases, so
// listeners matching them need them in scope; CostTracker is instantiable
// directly by callers via `extraListeners`.
export orca.events.{
  Announcement,
  OrcaEvent,
  OrcaListener,
  Pricing,
  PricingTable,
  ModelPricing,
  Usage,
  Cost,
  CostBasis,
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
  AutoApprove,
  PromptEvent,
  ToolSet,
  BackendTag,
  JsonData,
  Announce,
  schemaFromJsonData,
  codecFromJsonData
}
export orca.plan.{BugReportMatch, Plan, Task, Triage, Verdict, WithChat}
// PrSummary is the result type of openPrFromBranch and summarisePr;
// orcaCommentMarker is the idempotency marker gh.upsertComment keys on;
// recordOpenedPr is for a flow that opens its PR with a bare gh.createPr, and
// bodyWithOpenFindings builds that PR's body and reportOpenFindings prints
// the same section to the run output.
export orca.pr.{
  bodyWithOpenFindings,
  reportOpenFindings,
  openPrFromBranch,
  openPrIfGitHub,
  orcaCommentMarker,
  recordOpenedPr,
  summarisePr,
  PrSummary
}
// Reviewer-customisation surface: compose your own `List[Reviewer]` and
// `buildReviewers` it into the agents `reviewAndFixLoop` takes. OpenFinding(s)
// is the result type of reviewAndFixLoop/reviewThenFix, and OpenFinding.custom
// records a flow's own; Lint is constructed at the call site for their `lint`
// parameter and LintReport is what the summariser-taking `lint` returns;
// ReviewCheck is implemented for reviewAndFixLoop's `checks`. Location is a
// ReviewFinding and OpenFinding field type, and SkippedReview an OpenFindings
// one — needed by any flow that consumes findings.
export orca.review.{
  allReviewers,
  buildReviewers,
  lint,
  minimalReviewers,
  reviewAndFixLoop,
  reviewThenFix,
  Lint,
  LintReport,
  Location,
  OpenFinding,
  OpenFindings,
  Reviewer,
  ReviewerAgent,
  ReviewerCatalog,
  ReviewerPrompts,
  ReviewBatch,
  ReviewCheck,
  ReviewDiff,
  ReviewerSelector,
  ReviewFinding,
  ReviewResult,
  RosterEntry,
  SkippedReview
}
// PushFailure is the Left of GitTool.push's Either, NoDefaultBase of
// GitTool.defaultBase's; BuildWaitFailed the same for GitHubTool.waitForBuild;
// GitHubAvailability is what gh.availability() answers with, GitHubUnavailable
// the reason inside its Unavailable arm.
export orca.tools.{
  BuildOutcome,
  BuildStatus,
  BuildWaitFailed,
  Comment,
  GitHubAvailability,
  GitHubUnavailable,
  Issue,
  IssueHandle,
  NoDefaultBase,
  PrHandle,
  PushFailure
}
// Agent-override surface: the wiring an override factory receives, plus each
// backend's default-agent factory (`ClaudeAgents.default(w).opus`, …) and its
// model-tier extensions (`claude.opus`, `codex.mini`, …).
export orca.backend.AgentWiring
export orca.tools.claude.ClaudeAgents
export orca.tools.claude.ClaudeAgents.{
  haiku,
  sonnet,
  opus,
  fable,
  withNetworkTools
}
export orca.tools.codex.CodexAgents
export orca.tools.codex.CodexAgents.mini
export orca.tools.gemini.GeminiAgents
export orca.tools.gemini.GeminiAgents.flash
export orca.tools.pi.PiAgents
export orca.tools.opencode.OpencodeAgents
export orca.tools.opencode.OpencodeAgents.{
  anthropicOpus,
  anthropicSonnet,
  anthropicHaiku,
  openaiAstra,
  openaiSol,
  openaiLuna,
  withModel
}
export ox.either.orThrow

// Opaque types are aliased, not exported: an exported companion is reached
// through a forwarder `def`, and its members' types then see through the
// opaque type (scala/scala3#24051) — `ReviewerSlug("x")` would type as String.
// ReviewerSlug is Reviewer's name type, FindingId ReviewFinding's and
// OpenFinding's id type.
type Title = orca.plan.Title
val Title: orca.plan.Title.type = orca.plan.Title
type ReviewerSlug = orca.review.ReviewerSlug
val ReviewerSlug: orca.review.ReviewerSlug.type = orca.review.ReviewerSlug
type FindingId = orca.review.FindingId
val FindingId: orca.review.FindingId.type = orca.review.FindingId
type Model = orca.agents.Model
val Model: orca.agents.Model.type = orca.agents.Model
type OpencodeLauncher = orca.tools.opencode.OpencodeLauncher
val OpencodeLauncher: orca.tools.opencode.OpencodeLauncher.type =
  orca.tools.opencode.OpencodeLauncher
