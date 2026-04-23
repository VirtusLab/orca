package orca

import ox.scheduling.Schedule

import scala.concurrent.duration.DurationInt

case class LlmConfig(
    model: Option[String] = None,
    systemPrompt: Option[String] = None,
    autoApprove: AutoApprove = AutoApprove.All,
    onUnapproved: UnapprovedPolicy = UnapprovedPolicy.Deny,
    retrySchedule: Schedule = LlmConfig.defaultRetrySchedule
)

object LlmConfig:

  /** The default LlmConfig. Implementations may use reference-equality with
    * this singleton as a cheap "caller omitted the arg" sentinel, so always
    * refer to `LlmConfig.default` rather than constructing a fresh one when you
    * want default-config semantics.
    */
  val default: LlmConfig = LlmConfig()
  val defaultRetrySchedule: Schedule =
    Schedule.exponentialBackoff(1.second).maxRepeats(3)

enum AutoApprove:
  case All
  case Only(tools: Set[String])

enum UnapprovedPolicy:
  case Deny
  case AskUser
