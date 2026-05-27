package orca.llm

import ox.scheduling.Schedule

import scala.concurrent.duration.DurationInt

case class LlmConfig(
    model: Option[Model] = None,
    systemPrompt: Option[String] = None,
    autoApprove: AutoApprove = AutoApprove.All,
    /** Restrict the agent to read-only tools (Read / Glob / Grep / read-only
      * Bash). No file writes, no edits, no shelling out for side effects. Maps
      * to claude's `--permission-mode plan`. Used by planning helpers so the
      * agent can verify claims against the repo without making changes — an
      * over-eager planner editing files during a "plan-only" turn was the
      * motivating case.
      */
    readOnly: Boolean = false,
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
