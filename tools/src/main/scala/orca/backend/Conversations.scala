package orca.backend

import orca.events.{OrcaEvent, OrcaListener}
import orca.agents.{AutoApprove, BackendTag}

/** Drains a [[Conversation]] for the autonomous path, returning the awaited
  * `AgentResult`. Display events reach `events` through
  * [[ObservedConversation]], as on the interactive path.
  *
  * The [[ChannelEvent]]s that reach this drain are answered explicitly to avoid
  * blocking the subprocess: `ApproveTool` is auto-denied and `UserQuestion`
  * auto-answered, both also surfacing as `OrcaEvent.Error`.
  */
private[orca] object Conversations:

  /** Why the tool wasn't already approved. The auto-approve set is blamed only
    * when the tool really is outside it: a backend that ignores orca's set
    * (opencode, whose own server config answers `permission.asked`) can ask
    * about a tool the set already lists.
    */
  private def denialCause(toolName: String, autoApprove: AutoApprove): String =
    autoApprove match
      case AutoApprove.Only(tools) if !tools.contains(toolName) =>
        "it is not in the auto-approve set"
      case _ => "the backend asked for approval itself"

  def drainAutonomous[B <: BackendTag](
      conv: Conversation[B],
      autoApprove: AutoApprove,
      events: OrcaListener = OrcaListener.noop
  ): AgentResult[B] =
    ObservedConversation(conv, events).drain(
      answerUnattended(autoApprove, events)
    )

  private def answerUnattended(
      autoApprove: AutoApprove,
      events: OrcaListener
  )(event: ChannelEvent): Unit = event match
    case ConversationEvent.ApproveTool(toolName, _, respond) =>
      // The backend blocks waiting for our decision and autonomous mode has no
      // user to ask, so deny and surface as an error; dropping would deadlock.
      val cause = denialCause(toolName, autoApprove)
      respond(ApprovalDecision.Deny)
      events.onEvent(
        OrcaEvent.Error(
          s"Denied $toolName: $cause; autonomous mode cannot prompt"
        )
      )
      events.onEvent(OrcaEvent.ToolDenied(toolName, None))
    case ConversationEvent.UserQuestion(_, respond) =>
      // The ask_user MCP bridge isn't wired in autonomous mode (see
      // `ConversationMode.Autonomous`), so this should be unreachable. If it
      // ever fires, the bridge thread is blocked on `respond` — unblock it
      // rather than leak the thread.
      respond("[autonomous mode: no user available to answer]")
      events.onEvent(
        OrcaEvent.Error(
          "ask_user fired during an autonomous call; auto-answered"
        )
      )
