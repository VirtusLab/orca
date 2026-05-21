package orca.llm

import ox.scheduling.Schedule

import scala.concurrent.duration.DurationInt

case class LlmConfig(
    model: Option[String] = None,
    systemPrompt: Option[String] = None,
    autoApprove: AutoApprove = AutoApprove.All,
    /** What to do when the agent wants a tool that isn't covered by
      * `autoApprove`. Only consulted in interactive stages where the user is
      * present to answer; in autonomous stages the call is denied outright.
      */
    onUnapproved: UnapprovedPolicy = UnapprovedPolicy.Deny,
    retrySchedule: Schedule = LlmConfig.defaultRetrySchedule
):
  /** Return a config that also auto-approves the given tool, on top of whatever
    * the existing `autoApprove` allows. Backends use this to silently authorise
    * their own host-side tools (e.g. the MCP `ask_user`) without surfacing a
    * y/n prompt the user can't reasonably refuse.
    */
  def withAlsoAllowedTool(tool: String): LlmConfig =
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

enum UnapprovedPolicy:
  case Deny
  case AskUser
