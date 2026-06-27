package orca

import orca.events.OrcaEvent
import orca.tools.FsTool
import orca.tools.GitTool
import orca.tools.GitHubTool
import orca.agents.{
  Agent,
  BackendTag,
  ClaudeAgent,
  CodexAgent,
  GeminiAgent,
  OpencodeAgent,
  PiAgent
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
  /** Backend tag of the leading agent (the one the `flow(...)` selector
    * picked). A type *member* rather than a parameter, so `FlowContext` stays
    * unparametrised — every `using FlowContext` site is unaffected — while
    * [[agent]] is still concretely typed (`Agent[LeadB]`), which is what lets a
    * session thread across calls. The runtime captures the concrete tag here at
    * construction (`flow` is generic over it, inferred from the selector).
    */
  type LeadB <: BackendTag

  /** The leading agent. Reference it in a body instead of a concrete accessor
    * (`claude`/`codex`) so the flow is backend-agnostic — switch the `flow`
    * selector and the whole flow follows. A session from `agent.session`
    * threads into `agent.runSeeded` and the reviewers because [[LeadB]] pins
    * the backend.
    */
  def agent: Agent[LeadB]
  def claude: ClaudeAgent
  def codex: CodexAgent
  def opencode: OpencodeAgent
  def pi: PiAgent
  def gemini: GeminiAgent
  def git: GitTool
  def gh: GitHubTool
  def fs: FsTool
  def userPrompt: String
  def emit(event: OrcaEvent): Unit
