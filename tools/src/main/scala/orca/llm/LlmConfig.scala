package orca.llm

import ox.scheduling.Schedule

import scala.concurrent.duration.DurationInt

case class LlmConfig(
    model: Option[Model] = None,
    systemPrompt: Option[String] = None,
    /** Which tools auto-approve without a permission prompt. Only meaningful
      * for **interactive** sessions — autonomous turns have no prompt to
      * answer, so the field is inert there. Backends consult it only when
      * [[tools]] is [[ToolSet.Full]] (the read-only tiers are autonomous
      * planners/reviewers). `Only(set)` should list a subset of the tools
      * [[tools]] makes available; entries outside it are dead. Neither
      * invariant is type-enforced (one `LlmConfig` feeds both the interactive
      * and autonomous paths).
      */
    autoApprove: AutoApprove = AutoApprove.All,
    /** Which tools exist for the agent at all — the capability axis (distinct
      * from [[autoApprove]], the prompting axis). See [[ToolSet]] for the tiers
      * and their per-backend mapping. `Full` is write-capable; the read-only
      * tiers gate writes (hard on claude / gemini / opencode; prompt-only on pi
      * / codex under [[ToolSet.NetworkOnly]], where granting network forces a
      * writable shell).
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
    retrySchedule: Schedule = LlmConfig.defaultRetrySchedule
):
  /** Return a config whose `autoApprove` set also includes `tool`. Backends use
    * this to silently authorise their own host-side tools (e.g. the MCP
    * `ask_user`) without surfacing a y/n prompt the user can't reasonably
    * refuse. No-op when `autoApprove = AutoApprove.All` — everything is already
    * covered.
    */
  def autoApproveAlso(tool: String): LlmConfig =
    autoApprove match
      case AutoApprove.All => this
      case AutoApprove.Only(tools) =>
        copy(autoApprove = AutoApprove.Only(tools + tool))

object LlmConfig:

  // Must be declared before `default` so the case-class default arg resolves.
  val defaultRetrySchedule: Schedule =
    Schedule.exponentialBackoff(1.second).maxRetries(3)

  /** The default LlmConfig. Shared as a singleton so the framework can detect,
    * via `eq LlmConfig.default`, that the caller omitted the per-call `config`
    * argument; in that case the tool-level config (set via
    * `LlmTool.withConfig`) is used instead. Any explicit `LlmConfig` passed at
    * the call site wholly replaces the tool-level one — there is no per-field
    * merge. Pass `LlmConfig.default` (or omit the arg) to inherit the tool's
    * defaults; constructing a fresh `LlmConfig()` defeats the detection and
    * wipes them.
    */
  val default: LlmConfig = LlmConfig()

enum AutoApprove:
  case All
  case Only(tools: Set[String])

/** The set of tools available to the agent — the capability tier on
  * [[LlmConfig.tools]]. Each backend maps the tiers onto its own permission
  * model:
  *
  *   - **ReadOnly** — reads only; no shell, no edits. The hard no-edit gate
  *     planners and reviewers rely on (claude `--permission-mode plan`, codex
  *     `--sandbox read-only`, pi `--tools read,grep,find,ls`, gemini
  *     `--approval-mode plan`, opencode write/edit/bash/patch disabled).
  *   - **NetworkOnly** — reads plus network (web + GitHub), for planners that
  *     must read an issue/PR they were pointed at. Edits stay blocked where the
  *     backend can scope tools (claude adds a command-scoped allowlist;
  *     opencode / gemini already allow web in read-only). On pi and codex there
  *     is no network without a writable shell (`pi bash`, `codex
  *     workspace-write`), so there the no-edit guarantee is **prompt-only** —
  *     the planner prompts forbid edits.
  *   - **Full** — every tool, write-capable; prompting then follows
  *     [[LlmConfig.autoApprove]].
  */
enum ToolSet:
  case ReadOnly
  case NetworkOnly
  case Full
