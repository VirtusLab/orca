package orca

import orca.events.OrcaEvent
import orca.tools.FsTool
import orca.tools.GitTool
import orca.tools.GitHubTool
import orca.llm.{ClaudeTool, CodexTool}

/** Ambient context a flow script operates in. Bundles every tool the top- level
  * accessors (`claude`, `codex`, `git`, `gh`, `fs`) resolve against, the user's
  * positional prompt (`userPrompt`), and the event sink (`emit`) that
  * stage/fail/fixLoop and the library's internals publish to.
  *
  * One is built per `flow(...)` invocation — flow scripts don't normally
  * instantiate `FlowContext` directly, just call the accessors inside a
  * `flow(args): ...` block and let Scala 3's context functions resolve the
  * given instance.
  */
trait FlowContext:
  def claude: ClaudeTool
  def codex: CodexTool
  def git: GitTool
  def gh: GitHubTool
  def fs: FsTool
  def userPrompt: String
  def emit(event: OrcaEvent): Unit
