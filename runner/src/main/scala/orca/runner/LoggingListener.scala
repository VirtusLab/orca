package orca.runner

import orca.events.{OrcaEvent, OrcaListener}
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
    case OrcaEvent.StageStarted(name)   => log.info("stage start: {}", name)
    case OrcaEvent.StageCompleted(name) => log.info("stage done:  {}", name)
    case OrcaEvent.Step(message)        => log.info("step: {}", message)
    case OrcaEvent.Bookkeeping(message) =>
      log.info("bookkeeping: {}", message)
    case OrcaEvent.Caveat(message)  => log.info("caveat: {}", message)
    case OrcaEvent.UserPrompt(text) => log.debug("prompt sent:\n{}", text)
    case OrcaEvent.AssistantMessage(text, agent) =>
      log.debug("assistant ({}): {}", agent.getOrElse("?"), text)
    case OrcaEvent.ToolUse(tool, args, agent) =>
      log.debug("tool use ({}): {} {}", agent.getOrElse("?"), tool, args)
    case OrcaEvent.StructuredResult(raw, summary) =>
      // On a deliberately silent summary (`Some("")`) or a missing one
      // (`None`), log the raw JSON — display silence must not hide the result
      // from the trace.
      log.debug(
        "structured result: {}",
        summary.filter(_.nonEmpty).getOrElse(raw)
      )
    case t: OrcaEvent.TokensUsed =>
      log.debug(
        "tokens: agent={} role={} model={} attempt={} session={} cost={} usage={}",
        t.agent,
        t.role.getOrElse("(none)"),
        t.model.map(_.name).getOrElse("(unknown)"),
        t.attempt,
        t.session.getOrElse("(none)"),
        t.cost.fold("(none)")(_.amount.toString),
        t.usage
      )
    case OrcaEvent.Error(message, agent) =>
      log.error("error ({}): {}", agent.getOrElse("flow"), message)
    case e: OrcaEvent.SessionCommitted =>
      log.debug(
        "session committed: harness={} clientId={} wireId={} sessionName={} agent={} role={}",
        e.harness,
        e.clientId,
        e.wireId.getOrElse("(none)"),
        e.sessionName.getOrElse("(none)"),
        e.agent,
        e.role.getOrElse("(none)")
      )
