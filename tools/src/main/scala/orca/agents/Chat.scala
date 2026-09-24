package orca.agents

import orca.{InStage, OrcaFlowException}
import orca.backend.Dispatch

/** An EPHEMERAL multi-turn agent conversation — tool-using, workspace-editing,
  * exactly as capable as any other agent turn; "chat" names its lifetime, not
  * its powers. In-run only: nothing is recorded, so on a flow crash/resume the
  * conversation is gone (a durable `orca.FlowSession`, minted with
  * `agent.session(name, seed)`, is the door for conversations that must
  * survive).
  *
  * Runs need only [[InStage]], which crosses forks, so chats are the
  * conversation handle for parallel fan-outs: each fork mints its own
  * `agent.chat()` and drives multi-turn exchanges without resending context.
  * Interactive turns are the exception: run them from one thread at a time, as
  * they share the user's terminal.
  *
  * The handle bundles the minting [[Agent]] with a reserved [[SessionId]], so
  * every turn runs on the same agent configuration and conversation. Drive one
  * chat from one place at a time: concurrent turns against the same backend
  * conversation fail.
  *
  * A chat adopting a conversation another handle opened (such as a durable
  * session's `session.chat`) refuses a turn with [[ConversationNotHeld]] while
  * the backend does not hold that conversation: the turn would open it
  * unseeded.
  */
final class Chat[B <: BackendTag] private[agents] (
    private[orca] val agent: Agent[B],
    /** The underlying conversation id — library-internal; a durable session
      * hands out its conversation as `session.chat`.
      */
    private[orca] val id: SessionId[B],
    origin: ChatOrigin
):
  /** One free-text turn continuing this conversation. */
  def run(
      prompt: String,
      promptEvent: PromptEvent = PromptEvent.Emit
  )(using InStage): String =
    requireHeld()
    agent.runText(prompt, id, sessionKey = None, promptEvent = promptEvent)

  /** Fix the output type for structured turns continuing this conversation —
    * both `autonomous` and `interactive` modes, mirroring `agent.resultAs[O]`.
    */
  def resultAs[O: JsonData: Announce]: ChatCall[B, O] =
    new ChatCall(agent.resultAs[O], this)

  /** Checked before every turn, not when the chat is handed out: a handle read
    * on the flow thread may be used later in a fork, after the conversation was
    * opened or lost.
    */
  private[agents] def requireHeld(): Unit = origin match
    case ChatOrigin.Minted => ()
    case ChatOrigin.Adopted =>
      agent.dispatchFor(id) match
        case Dispatch.Resume(_, _) => ()
        case Dispatch.Fresh(_)     => throw new ConversationNotHeld

/** A turn on an adopted [[Chat]] whose conversation the backend does not hold.
  */
final class ConversationNotHeld
    extends OrcaFlowException(
      "chat turn refused: the backend does not hold this conversation (never " +
        "started, or lost on resume). For session.chat, run session.run or " +
        "session.resultAs[O].run on the flow thread first; otherwise start a " +
        "new agent.chat()"
    )

/** Whether a [[Chat]] opened its conversation or continues one another handle
  * opened.
  */
private[agents] enum ChatOrigin:
  /** Minted by `agent.chat()`: its first turn opens the conversation. */
  case Minted

  /** Continues a conversation opened elsewhere, which must still be held. */
  case Adopted

/** Structured gateway for a [[Chat]] (obtained via [[Chat.resultAs]]). Same
  * `autonomous` / `interactive` split as the one-shot `agent.resultAs[O]`
  * gateway; every turn continues the chat's conversation.
  */
final class ChatCall[B <: BackendTag, O] private[agents] (
    call: AgentCall[B, O],
    chat: Chat[B]
):
  object autonomous:
    def run[I: AgentInput](
        input: I,
        promptEvent: PromptEvent = PromptEvent.Emit
    )(using InStage): O =
      chat.requireHeld()
      call.autonomous.runWithSession(
        input,
        chat.id,
        sessionKey = None,
        promptEvent = promptEvent
      )

  object interactive:
    def run[I: AgentInput](input: I)(using InStage): O =
      chat.requireHeld()
      call.interactive.runWithSession(input, chat.id, sessionKey = None)
