package orca.tools.claude

import orca.agents.{AgentConfig, ClaudeAgent}
import orca.backend.AgentWiring
import orca.subprocess.OsProcCliRunner

/** Public constructors for the default claude agent. The concrete
  * [[DefaultClaudeAgent]] / [[ClaudeBackend]] stay `private[orca]`; this object
  * is the user-facing way to build a standard claude wired into a run (from a
  * `flow(...)` override factory) and reconfigure it with the trait's fluent
  * accessors (`.opus`, `.sonnet`, `.withConfig(...)`).
  */
object ClaudeAgents:

  /** The default claude agent for a run: Opus-1M lead model, standard config.
    * Bare `claude` needs the big window — the implementer session is
    * long-lived; `.sonnet` / `.haiku` opt down for cheap one-shots.
    * `cwdForProbe` is the run's `workDir` so a resumed worktree flow probes the
    * same directory the transcript lands under (see
    * [[ClaudeBackend.cwdForProbe]]).
    */
  def default(wiring: AgentWiring): ClaudeAgent =
    new DefaultClaudeAgent(
      backend =
        new ClaudeBackend(OsProcCliRunner, cwdForProbe = wiring.workDir),
      config = AgentConfig(model = Some(DefaultClaudeAgent.Opus1M)),
      prompts = wiring.prompts,
      workDir = wiring.workDir,
      events = wiring.events,
      interaction = wiring.interaction
    )
