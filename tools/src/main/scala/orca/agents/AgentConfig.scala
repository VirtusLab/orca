package orca.agents

import ox.scheduling.Schedule

import scala.concurrent.duration.DurationInt

case class AgentConfig(
    model: Option[Model] = None,
    /** Model used by [[orca.agents.Agent.cheap]] for incidental work (branch
      * naming, commit-message summaries, reviewer selection). `None` uses the
      * backend's built-in cheap tier.
      */
    cheapModel: Option[Model] = None,
    systemPrompt: Option[String] = None,
    /** Which tools auto-approve without a permission prompt. Only meaningful
      * for **interactive** sessions consulted only when [[tools]] is
      * [[ToolSet.Full]] — autonomous turns have no prompt to answer.
      * `Only(set)` should list a subset of what [[tools]] makes available;
      * entries outside it are dead. Neither invariant is type-enforced.
      */
    autoApprove: AutoApprove = AutoApprove.All,
    /** Which tools exist for the agent at all — the capability axis (distinct
      * from [[autoApprove]], the prompting axis). `Full` is write-capable, the
      * read-only tiers gate writes; how strongly each backend enforces that
      * gate is [[Enforcement]].
      */
    tools: ToolSet = ToolSet.Full,
    /** Let the agent manage git itself — suppresses the standing "runtime owns
      * git" rule that [[orca.backend.SystemPromptComposer]] otherwise appends
      * to every write-capable turn. Off by default: orca's model is that the
      * flow commits/branches/pushes via `git.*`, and a self-committing agent
      * empties the working tree (breaking `reviewAndFixLoop`'s diff-based
      * reviewer selection).
      */
    selfManagedGit: Boolean = false,
    retrySchedule: Schedule = AgentConfig.defaultRetrySchedule
):
  /** Return a config whose `autoApprove` set also includes `tool`. Backends use
    * this to silently authorise their own host-side tools (e.g. the MCP
    * `ask_user`). No-op when `autoApprove = AutoApprove.All`.
    */
  def autoApproveAlso(tool: String): AgentConfig =
    autoApprove match
      case AutoApprove.All => this
      case AutoApprove.Only(tools) =>
        copy(autoApprove = AutoApprove.Only(tools + tool))

object AgentConfig:

  val defaultRetrySchedule: Schedule =
    Schedule.exponentialBackoff(1.second).maxRetries(3)

enum AutoApprove:
  case All
  case Only(tools: Set[String])

/** How strongly a backend enforces the restriction a `(ToolSet, AutoApprove)`
  * combination requests. For the read-only tiers the restriction is "no
  * edits/shell"; for `Full` it is the approval policy itself.
  *
  *   - Hard — mechanically blocked (permission mode, sandbox, tool allowlist).
  *   - SandboxApprox — approximated by a coarser sandbox; semantics widened.
  *   - PromptOnly — only the prompt forbids it; the tools can physically do it.
  *   - Ignored — not encoded at all; behaviour depends on backend/server
  *     configuration outside orca's control.
  *
  * The per-backend mapping is machine-checked in
  * `runner/.../EnforcementTableTest.scala`; see each backend's
  * `*Args.enforcement` for the per-cell rationale.
  */
enum Enforcement:
  case Hard, SandboxApprox, PromptOnly, Ignored

/** The set of tools available to the agent — the capability tier on
  * [[AgentConfig.tools]]:
  *
  *   - **ReadOnly** — reads only; no shell, no edits. The no-edit gate planners
  *     and reviewers rely on.
  *   - **NetworkOnly** — reads plus read-only network (web + GitHub), for
  *     planners that must read an issue/PR they were pointed at.
  *   - **Full** — every tool, write-capable; prompting then follows
  *     [[AgentConfig.autoApprove]].
  *
  * How strongly each backend enforces these is captured as [[Enforcement]].
  * This enum only names the tier the caller asks for.
  */
enum ToolSet:
  case ReadOnly
  case NetworkOnly
  case Full
