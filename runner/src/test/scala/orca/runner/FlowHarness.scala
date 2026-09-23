package orca.runner

import orca.{
  AgentSet,
  BranchNamingStrategy,
  ConfigHome,
  OrcaArgs,
  StackSettings,
  runFlow
}
import orca.agents.Agent
import orca.backend.Interaction
import orca.events.{OrcaEvent, OrcaListener, Pricing}
import orca.testkit.TempDirs
import orca.runner.terminal.TerminalInteraction
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.atomic.AtomicReference

/** Drives `runFlow` end to end over stub agents, for the suites that assert on
  * what setup resolves before the body runs. The config home defaults to a
  * directory that doesn't exist, so no test reads the developer's `~/.config`.
  */
object FlowHarness:

  def driveFlow(
      workDir: os.Path,
      wiring: FlowWiring,
      flowName: String = "flow-harness",
      configHome: ConfigHome = absentConfigHome(),
      stackSettings: Option[StackSettings] = None,
      planningOverride: Option[AgentSet => Agent[?]] = None,
      codingOverride: Option[AgentSet => Agent[?]] = None,
      reviewOverride: Option[AgentSet => Agent[?]] = None,
      listeners: List[OrcaListener] = Nil
  )(body: orca.FlowControl ?=> Unit): Unit =
    supervised:
      val interaction = TerminalInteraction.start(
        out = new PrintStream(new ByteArrayOutputStream()),
        useColor = false,
        animated = false
      )
      runFlow(
        request(
          args = OrcaArgs(flowName),
          workDir = workDir,
          interaction = Some(interaction),
          extraListeners = listeners,
          branchNaming = None,
          stackSettings = stackSettings,
          planningAgent = planningOverride,
          codingAgent = codingOverride,
          reviewAgent = reviewOverride,
          configHome = configHome,
          wiring = wiring
        )
      )(body)

  /** A [[RunRequest]] with test defaults: nothing overridden, no
    * progress-header flow source, and a config home that doesn't exist.
    */
  def request(
      args: OrcaArgs,
      workDir: os.Path,
      interaction: Option[Interaction],
      extraListeners: List[OrcaListener] = Nil,
      branchNaming: Option[BranchNamingStrategy] = None,
      stackSettings: Option[StackSettings] = None,
      planningAgent: Option[AgentSet => Agent[?]] = None,
      codingAgent: Option[AgentSet => Agent[?]] = None,
      reviewAgent: Option[AgentSet => Agent[?]] = None,
      configHome: ConfigHome = absentConfigHome(),
      wiring: FlowWiring = FlowWiring()
  ): RunRequest =
    RunRequest(
      args = args,
      workDir = workDir,
      interaction = interaction,
      extraListeners = extraListeners,
      wiring = wiring,
      pricing = Pricing.default,
      setup = SetupOptions(
        branchNaming = branchNaming,
        stackSettings = stackSettings,
        roles = RoleOverrides(planningAgent, codingAgent, reviewAgent),
        configHome = configHome,
        flowSource = None
      )
    )

  /** A config home whose tier files and directories don't exist. */
  def absentConfigHome(): ConfigHome = ConfigHome(TempDirs.dir() / "orca")

  def recordSteps(sink: AtomicReference[List[String]]): OrcaListener =
    case OrcaEvent.Step(msg) => val _ = sink.updateAndGet(_ :+ msg)
    case _                   => ()
