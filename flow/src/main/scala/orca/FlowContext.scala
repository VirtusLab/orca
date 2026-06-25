package orca

import orca.events.OrcaEvent
import orca.tools.FsTool
import orca.tools.GitTool
import orca.tools.GitHubTool
import orca.llm.{
  ClaudeTool,
  CodexTool,
  GeminiTool,
  LlmTool,
  OpencodeTool,
  PiTool
}

import scala.annotation.implicitNotFound

/** Ambient context a flow script operates in. Bundles every tool the top- level
  * accessors (`claude`, `codex`, `opencode`, `pi`, `gemini`, `git`, `gh`, `fs`)
  * resolve against, the user's positional prompt (`userPrompt`), and the event
  * sink (`emit`) that stage/fail/fixLoop and the library's internals publish
  * to.
  *
  * One is built per `flow(...)` invocation — flow scripts don't normally
  * instantiate `FlowContext` directly, just call the accessors inside a
  * `flow(args): ...` block and let Scala 3's context functions resolve the
  * given instance.
  */
@implicitNotFound(
  "the flow tools (`claude`/`codex`/`git`/`gh`/`fs`/…), `display`, and `fail` are only available inside a `flow(...)` body. Wrap this code in `flow(OrcaArgs(args), _.claude): ...`."
)
trait FlowContext:
  /** The flow's leading model (the one passed to `flow(...)`). Flows use it for
    * planning/implementation; `llm.cheap` for incidental work.
    */
  def llm: LlmTool[?]
  def claude: ClaudeTool
  def codex: CodexTool
  def opencode: OpencodeTool
  def pi: PiTool
  def gemini: GeminiTool
  def git: GitTool
  def gh: GitHubTool
  def fs: FsTool
  def userPrompt: String
  def emit(event: OrcaEvent): Unit
