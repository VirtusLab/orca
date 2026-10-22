package orca.backend.pi

import orca.agents.{Agent, AgentConfig, PiAgent}
import orca.backend.AgentWiring
import orca.effect.{FileSystem, Processes}
import orca.subprocess.OsProcCliRunner

import java.time.Instant

/** The default pi agent. Pi supports many providers and fuzzy model patterns
  * through its own CLI, so it has no named tiers: pin one with
  * `pi.withModel(Model("..."))`.
  */
object PiAgents:

  /** The default pi agent for a run: standard config. */
  def default(wiring: AgentWiring): PiAgent =
    val fs = FileSystem.ambient
    Agent(
      backend = PiBackend.create(
        fs,
        OsProcCliRunner(Processes.ambient),
        PiBackend.transcriptProbe(fs, () => Instant.now()),
        wiring.workDir,
        Instant.now()
      ),
      config = AgentConfig(),
      prompts = wiring.prompts,
      events = wiring.events,
      interaction = wiring.interaction,
      defaultName = "pi"
    )
