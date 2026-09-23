package orca.testkit

import orca.agents.{AgentConfig, BackendTag, SessionId}
import orca.backend.{AgentBackend, Conversation, ConversationMode, TurnRequest}
import ox.Ox

/** Opens one interactive turn on a real backend and hands back the live
  * conversation, for tests that inspect what the backend spawned (argv, temp
  * files, MCP wiring) rather than run the whole turn.
  */
object OpenTurn:
  def interactive[B <: BackendTag](backend: AgentBackend[B])(
      prompt: String,
      session: SessionId[B],
      displayPrompt: String,
      config: AgentConfig,
      outputSchema: Option[String]
  )(using Ox): Conversation[B] =
    backend.open(
      TurnRequest(
        prompt,
        session,
        backend.sessions.dispatchFor(session),
        ConversationMode.Interactive(displayPrompt),
        config,
        outputSchema
      )
    )
