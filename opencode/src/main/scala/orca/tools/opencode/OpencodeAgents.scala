package orca.tools.opencode

import orca.agents.{Agent, AgentConfig, Model, OpencodeAgent}
import orca.backend.AgentWiring
import orca.subprocess.OsProcCliRunner
import ox.Ox

/** The default opencode agent and its provider-prefixed model accessors.
  * OpenCode spans providers, so the prefix keeps the vendor explicit at the
  * call site. The openai accessors follow OpenAI's durable capability tiers —
  * astra (flagship), sol (balanced), luna (efficiency) — the same way the
  * anthropic accessors follow opus/sonnet/haiku. The pinned ids are convenience
  * defaults — any id from `opencode models` is valid.
  */
object OpencodeAgents:

  /** The default opencode agent for a run: standard config, served through
    * `launcher` (defaults to a bare `opencode serve`). The backend pins a
    * shared `opencode serve` process to the run scope, so this needs the
    * ambient [[ox.Ox]].
    */
  def default(
      wiring: AgentWiring,
      launcher: OpencodeLauncher = OpencodeLauncher.default
  )(using Ox): OpencodeAgent =
    Agent(
      backend = OpencodeBackend(
        OsProcCliRunner,
        wiring.workDir,
        wiring.events,
        launcher
      ),
      config = AgentConfig(),
      prompts = wiring.prompts,
      events = wiring.events,
      interaction = wiring.interaction,
      defaultName = "main"
    )

  private[opencode] val AnthropicHaiku: Model =
    OpencodeModel("anthropic", "claude-haiku-4-5")
  private[opencode] val OpenaiLuna: Model =
    OpencodeModel("openai", "gpt-6-luna")

  extension (agent: OpencodeAgent)
    def anthropicOpus: OpencodeAgent =
      agent.withModel("anthropic", "claude-opus-5-5")
    def anthropicSonnet: OpencodeAgent =
      agent.withModel("anthropic", "claude-sonnet-5")
    def anthropicHaiku: OpencodeAgent = agent.withModel(AnthropicHaiku)
    def openaiAstra: OpencodeAgent = agent.withModel("openai", "gpt-6-astra")
    def openaiSol: OpencodeAgent = agent.withModel("openai", "gpt-6-sol")
    def openaiLuna: OpencodeAgent = agent.withModel(OpenaiLuna)

    /** Pin any `provider/model` id (e.g. `ollama/llama3.1`,
      * `myhost/qwen-coder`).
      */
    def withModel(providerModel: String): OpencodeAgent =
      agent.withModel(Model(providerModel))

    /** Two-arg form of `withModel`, e.g. `withModel("ollama", "llama3.1")`;
      * validates both parts.
      */
    def withModel(provider: String, modelId: String): OpencodeAgent =
      agent.withModel(OpencodeModel(provider, modelId))
