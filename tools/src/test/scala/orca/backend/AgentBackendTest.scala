package orca.backend

import orca.{OrcaFlowException, OrcaInteractiveCancelled}
import orca.agents.{
  AgentConfig,
  BackendTag,
  Model,
  SessionId,
  StructuredOutputMode
}
import orca.events.{OrcaListener, TurnDebit}
import orca.testkit.{ScriptedBackend, ScriptedTurn, StubEnforcementCell}
import ox.Ox

/** The turn shell every backend shares: [[AgentBackend.runAutonomous]] and
  * [[AgentBackend.runInteractive]] around a backend's [[AgentBackend.open]].
  */
class AgentBackendTest extends munit.FunSuite:

  private type Codex = BackendTag.Codex.type

  private val reportedWire = "server-thread-42"

  test("an autonomous turn commits the reported wire id and cancels once"):
    val live = scripted(Right(ScriptedBackend.result("out", reportedWire)))
    val backend = new OpeningBackend(live)
    val client = SessionId.fresh[Codex]
    val result = backend.runAutonomous("q", client, AgentConfig())
    assertEquals(result.output, "out")
    assertEquals(persisted(backend, client), Some(reportedWire))
    assertEquals(live.cancelCount.get(), 1)

  test("a failed autonomous turn propagates verbatim, commits nothing"):
    // Not relabelled with a backend-specific prefix, and still torn down.
    val failure = new OrcaFlowException("boom")
    val live = scripted(Left(failure))
    val backend = new OpeningBackend(live)
    val client = SessionId.fresh[Codex]
    val thrown = intercept[OrcaFlowException]:
      backend.runAutonomous("q", client, AgentConfig())
    assertEquals(thrown, failure)
    assertEquals(persisted(backend, client), None)
    assertEquals(live.cancelCount.get(), 1)

  test("an autonomous turn refuses an unsafe reported wire id"):
    // A crash before the backend reports its id leaves it empty; committing
    // that would let a later turn resume against an empty session id.
    val backend =
      new OpeningBackend(scripted(Right(ScriptedBackend.result("out", ""))))
    val client = SessionId.fresh[Codex]
    val thrown = intercept[OrcaFlowException]:
      backend.runAutonomous("q", client, AgentConfig())
    assert(thrown.getMessage.contains("invalid session id"), thrown.getMessage)
    assertEquals(persisted(backend, client), None)

  test("an interactive turn registers the driven result's wire id"):
    val live = scripted(Right(ScriptedBackend.result("out", reportedWire)))
    val backend = new OpeningBackend(live)
    val client = SessionId.fresh[Codex]
    val result = runInteractive(backend, client)
    assertEquals(result.output, "out")
    assertEquals(persisted(backend, client), Some(reportedWire))
    assertEquals(live.cancelCount.get(), 1)

  test("a cancelled interactive turn registers nothing"):
    val cancelled = new OrcaInteractiveCancelled(TurnDebit.Unobserved)
    val live = scripted(Left(cancelled))
    val backend = new OpeningBackend(live)
    val client = SessionId.fresh[Codex]
    val _ = intercept[OrcaInteractiveCancelled]:
      runInteractive(backend, client)
    assertEquals(persisted(backend, client), None)
    assertEquals(live.cancelCount.get(), 1)

  private def scripted(
      outcome: Either[Throwable, AgentResult[Codex]]
  ): ScriptedTurn[Codex] =
    new ScriptedTurn(Nil, outcome)

  private def persisted(
      backend: OpeningBackend,
      client: SessionId[Codex]
  ): Option[String] =
    backend.sessions
      .persistableWireId(client)
      .map(orca.agents.WireSessionId.value)

  private def runInteractive(
      backend: OpeningBackend,
      client: SessionId[Codex]
  ): AgentResult[Codex] =
    backend.runInteractive(
      "q",
      client,
      displayPrompt = "q",
      AgentConfig(),
      outputSchema = None,
      OrcaListener.noop,
      AwaitingInteraction
    )

  /** Hands out `live` for every turn. */
  private class OpeningBackend(live: LiveTurn[Codex])
      extends AgentBackend[Codex]
      with StubEnforcementCell[Codex]:
    val workDir: os.Path = os.pwd
    val tag: Codex = BackendTag.Codex
    val sessions: SessionSupport[Codex] =
      SessionSupport.durable(IdScheme.ServerMinted, _ => false)
    def structuredOutputMode: StructuredOutputMode =
      StructuredOutputMode.RawText
    def cheapModel(leading: Option[Model]): Option[Model] = None
    override protected[orca] def open(turn: TurnRequest[Codex])(using
        Ox
    ): LiveTurn[Codex] = live

  /** Answers with whatever the turn settles on. */
  private object AwaitingInteraction extends Interaction:
    def listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](
        live: ObservedTurn[B]
    ): AgentResult[B] =
      live.drain(_ => ())
