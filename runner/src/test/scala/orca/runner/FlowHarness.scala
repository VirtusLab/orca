package orca.runner

import orca.{AgentSet, OrcaArgs, StackSettings, runFlow}
import orca.agents.Agent
import orca.events.{OrcaEvent, OrcaListener}
import orca.testkit.TempDirs
import orca.runner.terminal.TerminalInteraction
import ox.supervised

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.atomic.AtomicReference

/** Drives `runFlow` end to end over stub agents, for the suites that assert on
  * what setup resolves before the body runs. Both user-global tiers default to
  * directories that don't exist, so no test reads the developer's `~/.config`.
  */
object FlowHarness:

  def driveFlow(
      workDir: os.Path,
      wiring: FlowWiring,
      flowName: String = "flow-harness",
      globalSettingsPath: os.Path = absentGlobalSettings(),
      globalReviewersPath: os.Path = absentGlobalReviewers(),
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
        args = OrcaArgs(flowName),
        workDir = workDir,
        interaction = Some(interaction),
        extraListeners = listeners,
        branchNaming = None,
        stackSettings = stackSettings,
        planningAgent = planningOverride,
        codingAgent = codingOverride,
        reviewAgent = reviewOverride,
        progressStore = None,
        globalSettingsPath = globalSettingsPath,
        globalReviewersPath = globalReviewersPath,
        wiring = wiring
      )(body)

  /** A global settings file that doesn't exist. */
  def absentGlobalSettings(): os.Path =
    TempDirs.dir() / "orca" / "settings.properties"

  /** A global reviewer directory that doesn't exist. */
  def absentGlobalReviewers(): os.Path = TempDirs.dir() / "orca" / "reviewers"

  def recordSteps(sink: AtomicReference[List[String]]): OrcaListener =
    case OrcaEvent.Step(msg) => val _ = sink.updateAndGet(_ :+ msg)
    case _                   => ()
