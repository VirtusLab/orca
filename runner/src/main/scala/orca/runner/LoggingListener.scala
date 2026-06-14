package orca.runner

import orca.events.{OrcaEvent, OrcaListener}
import org.slf4j.{LoggerFactory, MarkerFactory}

/** Mirrors every [[OrcaEvent]] into the slf4j log (logger `orca.flow`) so the
  * per-run trace file ([[OrcaLog]]) captures the whole execution: stage
  * transitions, each prompt sent to an agent (full text), assistant turns, tool
  * uses, steps (git / gh / recovery / …), structured results, token usage, and
  * errors. Wired into the flow's `EventDispatcher` alongside the cost tracker.
  *
  * The `Error` line carries the `TRACE_ONLY` marker, which the console appender
  * denies (see `logback.xml`): the terminal renderer owns the console, so
  * without the marker an `Error` (logged at ERROR) would print twice — once as
  * the renderer's `✖`, once as a logback line. The other lines are INFO/DEBUG,
  * already below the console's WARN threshold, so they need no marker.
  *
  * Messages are plain ASCII on purpose — the trace file is read back and dumped
  * to the console verbatim, and glyphs would corrupt under a non-UTF-8 console.
  * slf4j `{}` placeholders defer string building until an appender consumes the
  * event.
  */
private[orca] class LoggingListener extends OrcaListener:
  private val log = LoggerFactory.getLogger("orca.flow")
  private val traceOnly = MarkerFactory.getMarker("TRACE_ONLY")

  def onEvent(event: OrcaEvent): Unit = event match
    case OrcaEvent.StageStarted(name)      => log.info("stage start: {}", name)
    case OrcaEvent.StageCompleted(name, _) => log.info("stage done:  {}", name)
    case OrcaEvent.Step(message)           => log.info("step: {}", message)
    case OrcaEvent.UserPrompt(text)        => log.debug("prompt sent:\n{}", text)
    case OrcaEvent.AssistantMessage(text)  => log.debug("assistant: {}", text)
    case OrcaEvent.ToolUse(tool, args) =>
      log.debug("tool use: {} {}", tool, args)
    case OrcaEvent.StructuredResult(raw, summary) =>
      log.debug("structured result: {}", summary.getOrElse(raw))
    case OrcaEvent.TokensUsed(agent, model, usage) =>
      log.debug(
        "tokens: agent={} model={} usage={}",
        agent,
        model.map(_.name).getOrElse("(unknown)"),
        usage
      )
    case OrcaEvent.Error(message) => log.error(traceOnly, "error: {}", message)
