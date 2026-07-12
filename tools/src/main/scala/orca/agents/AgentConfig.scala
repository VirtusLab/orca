package orca.agents

import ox.scheduling.Schedule

import scala.concurrent.duration.DurationInt

case class AgentConfig(
    model: Option[Model] = None,
    /** Model used by [[orca.agents.Agent.cheap]] for incidental work (branch
      * naming, commit-message summaries, reviewer selection). `None` uses the
      * backend's built-in cheap tier; set it via `Agent.withCheapModel`.
      */
    cheapModel: Option[Model] = None,
    systemPrompt: Option[String] = None,
    /** Which tools auto-approve without a permission prompt. Only meaningful
      * for **interactive** sessions — autonomous turns have no prompt to
      * answer, so the field is inert there. Backends consult it only when
      * [[tools]] is [[ToolSet.Full]] (the read-only tiers are autonomous
      * planners/reviewers). `Only(set)` should list a subset of the tools
      * [[tools]] makes available; entries outside it are dead. Neither
      * invariant is type-enforced (one `AgentConfig` feeds both the interactive
      * and autonomous paths).
      */
    autoApprove: AutoApprove = AutoApprove.All,
    /** Which tools exist for the agent at all — the capability axis (distinct
      * from [[autoApprove]], the prompting axis). See [[ToolSet]] for the
      * tiers; `Full` is write-capable, the read-only tiers gate writes. How
      * strongly each backend actually enforces that gate is [[Enforcement]]
      * (e.g. `NetworkOnly` is hard on claude/gemini/opencode but prompt-only on
      * pi/codex, where granting network forces a writable shell).
      */
    tools: ToolSet = ToolSet.Full,
    /** Let the agent manage git itself — suppresses the standing "runtime owns
      * git" rule that [[orca.backend.SystemPromptComposer]] otherwise appends
      * to every write-capable turn. Off by default: orca's model is that the
      * flow commits/branches/pushes via `git.*`, and a self-committing agent
      * empties the working tree (breaking `reviewAndFixLoop`'s diff-based
      * reviewer selection). Flip it on only for a flow that genuinely wants the
      * agent to drive git.
      */
    selfManagedGit: Boolean = false,
    retrySchedule: Schedule = AgentConfig.defaultRetrySchedule
):
  /** Return a config whose `autoApprove` set also includes `tool`. Backends use
    * this to silently authorise their own host-side tools (e.g. the MCP
    * `ask_user`) without surfacing a y/n prompt the user can't reasonably
    * refuse. No-op when `autoApprove = AutoApprove.All` — everything is already
    * covered.
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
  *   - Ignored — not encoded at all; actual behavior depends on backend/server
  *     configuration outside orca's control.
  *
  * The per-backend mapping is machine-checked in
  * `runner/.../EnforcementTableTest.scala` and rendered in `AGENTS.md`; see
  * each backend's `*Args.enforcement` for the per-cell rationale.
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
  * How strongly each backend actually enforces these — and the approval policy
  * under `Full` — varies; that is captured as [[Enforcement]] and machine-
  * checked in `runner/.../EnforcementTableTest.scala` (rendered in
  * `AGENTS.md`), with per-cell rationale in each backend's `*Args.enforcement`.
  * This enum only names the tier the caller asks for.
  */
enum ToolSet:
  case ReadOnly
  case NetworkOnly
  case Full
