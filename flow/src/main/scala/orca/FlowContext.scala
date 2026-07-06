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
  /** Backend tag of the leading agent. You never write this — it's the backend
    * of the agent your `flow(...)` selector picked, and it's why `agent` is
    * precisely typed (`Agent[LeadB]`) so sessions thread. (A type *member*
    * rather than a parameter, so `FlowContext` stays unparametrised — every
    * `using FlowContext` site is unaffected; the runtime captures the concrete
    * tag here at construction, with `flow` generic over it, inferred from the
    * selector.)
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

  /** Exactly-once error reporting: the runtime marks a throwable here when it
    * publishes an `OrcaEvent.Error` for it, and every enclosing frame (nested
    * stages, the flow boundary) checks before re-reporting. Identity-based
    * (`eq`) — the mark travels with the object, not its type or message, so
    * plain RuntimeExceptions are covered too. `private[orca]`: user code never
    * participates.
    *
    * The contract assumes a freshly-constructed throwable per failure (as every
    * failure site in orca produces): identity marking would suppress a
    * semantically-new failure that happened to reuse a cached/singleton
    * exception instance — nothing in orca does that today.
    */
  private[orca] def markErrorReported(e: Throwable): Unit
  private[orca] def errorAlreadyReported(e: Throwable): Boolean

  /** Emit-once helper over the two primitives: runs `emit` and marks `e` only
    * when `e` hasn't been reported yet. The runtime's three report sites all
    * use this shape.
    */
  private[orca] final def reportOnce(e: Throwable)(emit: => Unit): Unit =
    if !errorAlreadyReported(e) then
      emit
      markErrorReported(e)
