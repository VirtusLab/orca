package orca.agents

import orca.backend.{
  Conversation,
  Interaction,
  AgentBackend,
  AgentResult,
  SessionRegistry,
  SessionSupport
}
import orca.events.OrcaListener

/** `withCheapModel` pins the model that [[Agent.cheap]] resolves to, overriding
  * the backend default, and the override rides on the config so it survives
  * other builders.
  */
class WithCheapModelTest extends munit.FunSuite:

  test("no override: cheap falls back to defaultCheap"):
    // The stub has no cheaper tier, so defaultCheap is `this`.
    assertEquals(newTool().cheap.name, "model:none")

  test("withCheapModel: cheap pins the given model"):
    assertEquals(
      newTool().withCheapModel(Model("cheap-x")).cheap.name,
      "model:cheap-x"
    )

  test("the override rides on the config through other builders"):
    assertEquals(
      newTool().withCheapModel(Model("cheap-x")).withReadOnly.cheap.name,
      "model:cheap-x"
    )

  private def newTool(): Agent[BackendTag.Pi.type] = new StubTool(
    AgentConfig()
  )

  /** Minimal real `BaseAgent` whose `name` reflects the pinned model, so the
    * model `cheap` lands on is observable. `copyTool` threads the config
    * (unlike a degenerate `this`-returning stub), which is exactly what
    * `withCheapModel` relies on.
    */
  private class StubTool(cfg: AgentConfig)
      extends BaseAgent[BackendTag.Pi.type, Agent[BackendTag.Pi.type]](
        StubBackend,
        cfg,
        StubPrompts,
        os.temp.dir(),
        OrcaListener.noop,
        StubInteraction
      ):
    val name: String = "model:" + cfg.model.map(Model.name).getOrElse("none")
    protected def copyTool(
        config: AgentConfig = cfg,
        name: String = name
    ): Agent[BackendTag.Pi.type] = new StubTool(config)

  private object StubBackend extends AgentBackend[BackendTag.Pi.type]:
    def runAutonomous(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        config: AgentConfig,
        workDir: os.Path,
        events: OrcaListener,
        outputSchema: Option[String]
    ): AgentResult[BackendTag.Pi.type] = ???
    def runInteractive(
        prompt: String,
        session: SessionId[BackendTag.Pi.type],
        displayPrompt: String,
        config: AgentConfig,
        workDir: os.Path,
        outputSchema: Option[String]
    )(using ox.Ox): Conversation[BackendTag.Pi.type] = ???
    val sessions: SessionSupport[BackendTag.Pi.type] =
      SessionSupport.Ephemeral(new SessionRegistry.ClaimedOnce)
    val tag: BackendTag.Pi.type = BackendTag.Pi

  private object StubPrompts extends Prompts:
    def autonomous(
        input: String,
        outputSchema: String,
        config: AgentConfig
    ): String = ???
    def interactive(
        input: String,
        outputSchema: String,
        config: AgentConfig
    ): String = ???
    def retry(failedResponse: String, parseError: String): String = ???

  private object StubInteraction extends Interaction:
    def listeners: List[OrcaListener] = Nil
    def drive[B <: BackendTag](conversation: Conversation[B]): AgentResult[B] =
      ???
