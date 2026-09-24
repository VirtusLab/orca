package orca.runner

import orca.events.{Announcement, OrcaEvent, OrcaListener}
import org.slf4j.LoggerFactory

/** Mirrors every [[OrcaEvent]] into the slf4j log (logger `orca.flow`) so the
  * per-run trace file ([[OrcaLog]]) captures the whole execution. Wired into
  * the flow's `EventDispatcher` alongside the cost tracker.
  *
  * A trace mirror, not a console channel: the whole `orca.*` tree routes to the
  * trace file only ([[OrcaLog]] makes it non-additive), so even the ERROR line
  * never reaches the console — the terminal renderer owns that.
  *
  * Messages are plain ASCII on purpose: the trace file is dumped to the console
  * verbatim, and glyphs would corrupt under a non-UTF-8 console.
  */
private[orca] class LoggingListener extends OrcaListener:
  private val log = LoggerFactory.getLogger("orca.flow")

  def onEvent(event: OrcaEvent): Unit = event match
    case OrcaEvent.StageStarted(path) =>
      log.info("stage start: {}", path.display)
    case OrcaEvent.StageEnded(path, outcome) =>
      log.info("stage end: {} ({})", path.display, outcome)
    case OrcaEvent.ActivityStarted(label) =>
      log.info("activity start: {}", label)
    case _: OrcaEvent.ActivityEnded => log.info("activity end")
    case OrcaEvent.Step(message)    => log.info("step: {}", message)
    case OrcaEvent.Bookkeeping(message) =>
      log.info("bookkeeping: {}", message)
    case OrcaEvent.Caveat(message)     => log.info("caveat: {}", message)
    case OrcaEvent.BranchBound(branch) => log.info("branch bound: {}", branch)
    case OrcaEvent.UserPrompt(text)    => log.debug("prompt sent:\n{}", text)
    case OrcaEvent.AssistantMessage(text, agent) =>
      log.debug("assistant ({}): {}", agent.getOrElse("?"), text)
    case OrcaEvent.ToolUse(tool, args, agent) =>
      log.debug("tool use ({}): {} {}", agent.getOrElse("?"), tool, args)
    case OrcaEvent.ToolDenied(tool, agent) =>
      log.info("tool denied ({}): {}", agent.getOrElse("?"), tool)
    case OrcaEvent.StructuredResult(raw, announcement, agent) =>
      // Display silence must not hide the result from the trace.
      val text = announcement match
        case Announcement.Say(summary)                      => summary
        case Announcement.Silent | Announcement.Unannounced => raw
      log.debug(
        "structured result ({}): {}",
        agent.getOrElse("interactive"),
        text
      )
    case _: OrcaEvent.UnpricedTurn =>
      () // Priced by the dispatcher in front of this listener.
    case OrcaEvent.TokensUsed(t, cost) =>
      log.debug(
        "tokens: agent={} role={} model={} turn={} conversationKey={} cost={} usage={}",
        t.agent,
        t.role.getOrElse("(none)"),
        t.model.map(_.name).getOrElse("(unknown)"),
        t.turn,
        t.conversationKey,
        cost.fold("(none)")(_.amount.toString),
        t.usage
      )
    case OrcaEvent.Error(message, agent) =>
      log.error("error ({}): {}", agent.getOrElse("flow"), message)
    case e: OrcaEvent.SessionCommitted =>
      log.debug(
        "session committed: backend={} clientId={} wireId={} session={} agent={} role={}",
        e.backend,
        e.clientId,
        e.wireId.getOrElse("(none)"),
        e.sessionKey.fold("(none)")(_.describe),
        e.agent,
        e.role.getOrElse("(none)")
      )
