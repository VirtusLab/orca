package orca.agents

import orca.InStage

/** An EPHEMERAL multi-turn agent conversation — tool-using, workspace-editing,
  * exactly as capable as any other agent turn; "chat" names its lifetime, not
  * its powers. In-run only: nothing is recorded, so on a flow crash/resume the
  * conversation is gone (a durable `orca.FlowSession`, minted with
  * `agent.session(name, seed)`, is the door for conversations that must
  * survive).
  *
  * Runs need only [[InStage]] — a shared capability that crosses forks — so
  * chats are the conversation handle for parallel fan-outs: each fork mints its
  * own `agent.chat()` and drives multi-turn exchanges without resending context
  * (the review loop's reviewers work exactly this way).
  *
  * The handle bundles the minting [[Agent]] with a reserved [[SessionId]], so
  * every turn runs on the same agent configuration and the same conversation —
  * there is no loose id to mis-route. Drive one chat from one place at a time:
  * concurrent turns against the same backend conversation fail.
  */
final class Chat[B <: BackendTag] private[orca] (
    private[orca] val agent: Agent[B],
    /** The underlying conversation id. `private[orca]` — the library's own
      * continuations (the planning grid) reach it; scripts hold the chat
      * itself, and the only public id-adoption door is `agent.chat(id)` over a
      * `FlowSession.id`.
      */
    private[orca] val id: SessionId[B]
):
  /** One free-text turn continuing this conversation. */
  def run(
      prompt: String,
      config: Option[AgentConfig] = None,
      emitPrompt: Boolean = true
  )(using InStage): String =
    agent.autonomous.runWithSession(prompt, id, config, emitPrompt)

  /** Fix the output type for structured turns continuing this conversation —
    * both `autonomous` and `interactive` modes, mirroring `agent.resultAs[O]`.
    */
  def resultAs[O: JsonData: Announce]: ChatCall[B, O] =
    new ChatCall(agent.resultAs[O], id)

/** Structured gateway for a [[Chat]] (obtained via [[Chat.resultAs]]). Same
  * `autonomous` / `interactive` split as the one-shot `agent.resultAs[O]`
  * gateway; every turn continues the chat's conversation.
  */
final class ChatCall[B <: BackendTag, O] private[orca] (
    call: AgentCall[B, O],
    id: SessionId[B]
):
  object autonomous:
    def run[I: AgentInput](
        input: I,
        config: Option[AgentConfig] = None,
        emitPrompt: Boolean = true
    )(using InStage): O =
      call.autonomous.runWithSession(input, id, config, emitPrompt)

  object interactive:
    def run[I: AgentInput](
        input: I,
        config: Option[AgentConfig] = None
    )(using InStage): O =
      call.interactive.runWithSession(input, id, config)
