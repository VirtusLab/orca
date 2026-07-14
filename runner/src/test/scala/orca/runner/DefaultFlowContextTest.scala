package orca.runner

import orca.{FlowContext, StackSettings}
import orca.agents.{
  Announce,
  AutonomousTextCall,
  BackendTag,
  ClaudeAgent,
  CodexAgent,
  GeminiAgent,
  JsonData,
  AgentCall,
  AgentConfig,
  Model,
  OpencodeAgent,
  PiAgent,
  ToolSet
}
import orca.events.EventDispatcher
import orca.progress.ProgressStore
import orca.tools.{OsFsTool, OsGitHubTool, OsGitTool}
import orca.subprocess.OsProcCliRunner
import orca.testkit.TempDirs

/** Pins that [[DefaultFlowContext.close]] is a best-effort fan-out: one agent's
  * `close()` throwing must not stop the others from being closed (ADR 0018 — a
  * leaked backend resource, e.g. opencode's `serve` process, must never be
  * masked by an earlier agent's failure). Also pins the one-shot
  * `stackSettings` slot the lifecycle populates during setup (ADR 0019).
  */
class DefaultFlowContextTest extends munit.FunSuite:

  test(
    "close() closes every agent even when an earlier one's close() throws"
  ):
    var codexClosed = false
    val ctx = newContext(codex = new RecordingCodex(() => codexClosed = true))
    ctx.close()
    assert(
      codexClosed,
      "codex.close() must run despite claude.close() throwing"
    )

  test(
    "stackSettings slot: read-before-populate throws, populate-then-read returns the value, double-populate throws"
  ):
    val ctx = newContext()
    val unpopulated = intercept[IllegalStateException](ctx.stackSettings)
    assert(
      unpopulated.getMessage.contains("before lifecycle setup"),
      s"unpopulated read must point at the lifecycle: ${unpopulated.getMessage}"
    )
    val settings = StackSettings(format = List("cargo fmt"))
    ctx.populateStackSettings(settings)
    assertEquals(ctx.stackSettings, settings)
    val doublePopulate = intercept[IllegalStateException](
      ctx.populateStackSettings(StackSettings.empty)
    )
    assert(
      doublePopulate.getMessage.contains("already populated"),
      s"double populate must name the violation: ${doublePopulate.getMessage}"
    )

  /** A context over throwaway tools and the throwing/noop agent stubs below;
    * `codex` is the only agent a test swaps (to record its close).
    */
  private def newContext(
      codex: CodexAgent = new RecordingCodex(() => ())
  ): DefaultFlowContext[BackendTag.ClaudeCode.type] =
    val workDir = TempDirs.dir()
    new DefaultFlowContext[BackendTag.ClaudeCode.type](
      userPrompt = "test",
      workDir = workDir,
      dispatcher = new EventDispatcher(Nil),
      agentSelector = (_: FlowContext) => ThrowingClaude,
      claude = ThrowingClaude,
      codex = codex,
      opencode = NoopOpencode,
      pi = NoopPi,
      gemini = NoopGemini,
      git = new OsGitTool(workDir),
      gh = new OsGitHubTool(OsProcCliRunner, workDir),
      fs = new OsFsTool(workDir),
      progressStore = ProgressStore.default(workDir, "test")
    )

  /** Throws from every LLM call and from `close()` — pins that a throwing
    * `close()` doesn't abort the fan-out over the other agents.
    */
  private object ThrowingClaude extends ClaudeAgent:
    val name = "throwing-claude"
    def haiku = this
    def sonnet = this
    def opus = this
    def fable = this
    def withModel(model: Model) = this
    def withNetworkTools(t: Seq[String]) = this
    def withConfig(c: AgentConfig) = this
    def withSystemPrompt(p: String) = this
    def withName(n: String) = this
    def withTools(tools: ToolSet) = this
    def autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
      throw new UnsupportedOperationException
    def resultAs[O: JsonData: Announce]
        : AgentCall[BackendTag.ClaudeCode.type, O] =
      throw new UnsupportedOperationException
    override private[orca] def close(): Unit =
      throw new RuntimeException("boom")

  /** Records that `close()` ran via `onClose`, positioned after `claude` in
    * `DefaultFlowContext.close()`'s fan-out order.
    */
  private class RecordingCodex(onClose: () => Unit) extends CodexAgent:
    val name = "recording-codex"
    def mini = this
    def withModel(model: Model) = this
    def withConfig(c: AgentConfig) = this
    def withSystemPrompt(p: String) = this
    def withName(n: String) = this
    def withTools(tools: ToolSet) = this
    def autonomous: AutonomousTextCall[BackendTag.Codex.type] =
      throw new UnsupportedOperationException
    def resultAs[O: JsonData: Announce]: AgentCall[BackendTag.Codex.type, O] =
      throw new UnsupportedOperationException
    override private[orca] def close(): Unit = onClose()

  private object NoopOpencode extends OpencodeAgent:
    val name = "noop-opencode"
    def anthropicOpus = this
    def anthropicSonnet = this
    def anthropicHaiku = this
    def openaiSol = this
    def openaiTerra = this
    def openaiLuna = this
    def withModel(providerModel: String) = this
    def withConfig(c: AgentConfig) = this
    def withSystemPrompt(p: String) = this
    def withName(n: String) = this
    def withTools(tools: ToolSet) = this
    def autonomous: AutonomousTextCall[BackendTag.Opencode.type] =
      throw new UnsupportedOperationException
    def resultAs[O: JsonData: Announce]
        : AgentCall[BackendTag.Opencode.type, O] =
      throw new UnsupportedOperationException

  private object NoopPi extends PiAgent:
    val name = "noop-pi"
    def withModel(model: Model) = this
    def withConfig(c: AgentConfig) = this
    def withSystemPrompt(p: String) = this
    def withName(n: String) = this
    def withTools(tools: ToolSet) = this
    def autonomous: AutonomousTextCall[BackendTag.Pi.type] =
      throw new UnsupportedOperationException
    def resultAs[O: JsonData: Announce]: AgentCall[BackendTag.Pi.type, O] =
      throw new UnsupportedOperationException

  private object NoopGemini extends GeminiAgent:
    val name = "noop-gemini"
    def flash = this
    def withModel(model: Model) = this
    def withConfig(c: AgentConfig) = this
    def withSystemPrompt(p: String) = this
    def withName(n: String) = this
    def withTools(tools: ToolSet) = this
    def autonomous: AutonomousTextCall[BackendTag.Gemini.type] =
      throw new UnsupportedOperationException
    def resultAs[O: JsonData: Announce]: AgentCall[BackendTag.Gemini.type, O] =
      throw new UnsupportedOperationException
