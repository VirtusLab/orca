package orca.tools.claude

import orca.agents.{AgentConfig, ClaudeAgent}
import orca.backend.AgentWiring
import orca.subprocess.OsProcCliRunner

/** Public constructors for the default claude agent: the concrete
  * [[DefaultClaudeAgent]] / [[ClaudeBackend]] stay `private[orca]`, so this is
  * the user-facing way to build a standard claude wired into a run.
  */
object ClaudeAgents:

  /** The default claude agent for a run: Opus-1M lead model, standard config.
    * The backend's `workDir` is the run's `workDir` so a resumed worktree flow
    * probes the same directory the transcript lands under (see
    * [[ClaudeBackend.workDir]]).
    */
  def default(wiring: AgentWiring): ClaudeAgent =
    new DefaultClaudeAgent(
      backend = new ClaudeBackend(OsProcCliRunner, workDir = wiring.workDir),
      config = AgentConfig(model = Some(DefaultClaudeAgent.Opus1M)),
      prompts = wiring.prompts,
      events = wiring.events,
      interaction = wiring.interaction
    )
