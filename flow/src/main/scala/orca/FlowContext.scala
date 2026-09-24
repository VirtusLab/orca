package orca

import orca.events.OrcaEvent
import orca.tools.FsTool
import orca.tools.{GitTool, RuntimeGit}
import orca.tools.GitHubTool
import orca.agents.Agent
import orca.review.ReviewerCatalog

import scala.annotation.implicitNotFound

/** Ambient context a flow script operates in. Bundles every tool the top- level
  * accessors (`claude`, `codex`, `opencode`, `pi`, `gemini`, `git`, `gh`, `fs`)
  * resolve against and the user's positional prompt (`userPrompt`).
  *
  * One is built per `flow(...)` invocation — flow scripts don't normally
  * instantiate `FlowContext` directly, just call the accessors inside a
  * `flow(args): ...` block, which provides one.
  *
  * The five per-backend accessors (`claude`, `codex`, …) come from
  * [[AgentSet]]. The three role accessors ([[planningAgent]] / [[codingAgent]]
  * / [[reviewAgent]], ADR 0020) are resolved from settings against that set
  * before the context exists, each landing on its own backend.
  */
@implicitNotFound(
  "the flow tools (`claude`/`codex`/`git`/`gh`/`fs`/…), `display`, and `fail` are only available inside a `flow(...)` body. Wrap this code in `flow(OrcaArgs(args)): ...`, or declare `(using FlowContext)` on this helper."
)
trait FlowContext extends AgentSet:
  /** The planning-role agent (ADR 0020): resolved from settings, default
    * claude. Scripts hand it to `Plan.*`.
    */
  def planningAgent: Agent[?]

  /** The coding-role agent — the run's primary: implementer sessions, branch
    * naming, stack discovery, and default commit messages run here.
    */
  def codingAgent: Agent[?]

  /** The review-role agent: `allReviewers(reviewAgent)`, the reviewer-picker
    * and the lint summariser default to its tiers.
    */
  def reviewAgent: Agent[?]

  /** The git handle scripts see: reads and pushes. */
  final def git: GitTool = runtimeGit

  /** The same git with the runtime's branch, commit and teardown operations —
    * for the flow runtime, never for scripts.
    */
  private[orca] def runtimeGit: RuntimeGit

  def gh: GitHubTool
  def fs: FsTool

  /** The working tree the flow runs against. */
  def workDir: os.Path

  /** Resolved stack settings (ADR 0019): resolved once during lifecycle setup —
    * override > `.orca/settings.properties` > auto-discovery — and frozen for
    * the run.
    */
  def stackSettings: StackSettings

  /** The reviewer definitions this run works from: the shipped set with the
    * project's `.orca/reviewers/` and the user-global directory layered over
    * it. Resolved once at run start, like [[stackSettings]], and frozen for the
    * run — what [[orca.review.allReviewers]] and
    * [[orca.review.minimalReviewers]] build their agents from.
    */
  def reviewerCatalog: ReviewerCatalog

  def userPrompt: String

  /** The library's event sink. Flows use `display`, `fail` and `.announce`. */
  private[orca] def emit(event: OrcaEvent): Unit
