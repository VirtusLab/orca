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
  *     On `Full` the gated boundary may sit at a documented SUPERSET of the
  *     request: claude's `--allowedTools` adds to its default permission mode's
  *     approvals rather than confining the agent to the names listed, so the
  *     approved set is those defaults ∪ the `Only` list.
  *   - SandboxApprox — approximated by a coarser sandbox; semantics widened.
  *   - PromptOnly — only the prompt forbids it; the tools can physically do it.
  *     [[orca.backend.SystemPromptComposer.ReadOnlyTurn]] is what puts that
  *     prose on every read-only turn.
  *   - Ignored — not encoded at all; behaviour depends on backend/server
  *     configuration outside orca's control.
  *
  * This is the canonical statement of what the levels mean; the per-backend
  * mapping is machine-checked in `runner/.../EnforcementTableTest.scala`, and
  * per-cell rationale lives in each backend's `*Args.enforcementCell`.
  */
enum Enforcement:
  case Hard, SandboxApprox, PromptOnly, Ignored

/** Whether a turn starts a backend session or continues one — the second axis
  * of the enforcement matrix, alongside `(ToolSet, AutoApprove)`. It matters
  * because a backend may put its restriction flags on the spawn only: codex
  * `exec resume` rejects `--sandbox`/`--full-auto`, so a resumed turn runs in
  * the sandbox its session was created with, whatever tier the caller asked
  * for.
  *
  * Derived from [[orca.backend.Dispatch]], which carries the wire ids this
  * classification has no use for.
  */
enum TurnDispatch:
  case Fresh, Resumed

/** A backend's answer for one cell of the enforcement matrix: the level, and
  * why it is that level. One value rather than two, so a backend whose flags
  * change cannot have its level updated while a stale reason stays beside it.
  *
  * `rationale` is a single unwrapped sentence or two naming the mechanism (the
  * flag, sandbox, or absence of one) that produces `level`. It is the only home
  * for that text: AGENTS.md's table is rendered from the levels alone.
  */
case class EnforcementCell(level: Enforcement, rationale: String)

/** The set of tools available to the agent — the capability tier on
  * [[AgentConfig.tools]]:
  *
  *   - **ReadOnly** — reads only; no shell, no edits. The no-edit gate planners
  *     and reviewers rely on.
  *   - **NetworkOnly** — reads plus read-only network (web, and on claude a
  *     host-served GitHub issue/PR read), for planners that must read an
  *     issue/PR they were pointed at.
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
