package orca.tools.opencode

import orca.agents.{AgentConfig, OpencodeAgent}
import orca.backend.AgentWiring
import orca.subprocess.OsProcCliRunner
import ox.Ox

/** Public constructors for the default opencode agent. The concrete
  * [[DefaultOpencodeAgent]] / [[OpencodeBackend]] stay `private[orca]`; this
  * object is the user-facing way to build a standard opencode wired into a run.
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
    new DefaultOpencodeAgent(
      backend = OpencodeBackend(OsProcCliRunner, wiring.workDir, launcher),
      config = AgentConfig.default,
      prompts = wiring.prompts,
      workDir = wiring.workDir,
      events = wiring.events,
      interaction = wiring.interaction
    )
