package orca.runner

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.FileAppender
import org.slf4j.{Logger, LoggerFactory}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.NonFatal

/** Per-run execution-trace log.
  *
  * [[start]] creates a fresh temp file and attaches a DEBUG-level logback
  * `FileAppender` to the `orca` logger, made **non-additive** — so the whole
  * `orca.*` tree (events via [[LoggingListener]], subprocess invocations via
  * `orca.proc`) lands in the file and never propagates to the root console
  * appender. The terminal renderer owns the console; orca's logging is purely
  * the trace. Framework chatter (netty/tapir/…) is on its own loggers and still
  * reaches the console's WARN appender, unaffected.
  *
  * The file is intentionally NOT deleted on exit, so it can be inspected after
  * the run. If logback isn't the active slf4j backend, or the temp file can't be
  * created, file logging is skipped (best-effort) rather than failing the flow —
  * [[file]] is then `None`.
  */
private[orca] final class OrcaLog private (
    val file: Option[os.Path],
    appender: Option[FileAppender[ILoggingEvent]],
    target: Option[ch.qos.logback.classic.Logger],
    previousAdditive: Boolean
):
  private val finished = new AtomicBoolean(false)

  /** Detach and stop the per-run file appender and restore the `orca` logger's
    * additivity. The trace is left on disk for inspection. Idempotent — safe to
    * call from both the error path (before `System.exit`) and the success path.
    */
  def finish(): Unit =
    if finished.compareAndSet(false, true) then
      appender.foreach(_.stop())
      for a <- appender; t <- target do
        t.detachAppender(a)
        t.setAdditive(previousAdditive)

private[orca] object OrcaLog:
  /** Attach a fresh per-run DEBUG file appender and return the handle. Must be
    * called before the flow does any logging so the whole run is captured.
    */
  def start(): OrcaLog =
    val maybeFile =
      try Some(os.temp(prefix = "orca-", suffix = ".log", deleteOnExit = false))
      catch case NonFatal(_) => None
    (maybeFile, loggerContext()) match
      case (Some(file), Some(ctx)) =>
        val encoder = new PatternLayoutEncoder
        encoder.setContext(ctx)
        encoder.setPattern("%d{HH:mm:ss.SSS} %-5level %logger{24} - %msg%n")
        encoder.setCharset(UTF_8)
        encoder.start()

        val appender = new FileAppender[ILoggingEvent]
        appender.setContext(ctx)
        appender.setName("orca-run-trace")
        appender.setFile(file.toString)
        appender.setAppend(false)
        appender.setEncoder(encoder)
        appender.start()

        val orcaLogger = ctx.getLogger("orca")
        val previousAdditive = orcaLogger.isAdditive
        orcaLogger.addAppender(appender)
        orcaLogger.setAdditive(false) // orca.* → file only, never the console
        new OrcaLog(
          Some(file),
          Some(appender),
          Some(orcaLogger),
          previousAdditive
        )
      case (file, _) =>
        new OrcaLog(file, None, None, previousAdditive = true)

  /** The bound logback `LoggerContext`. Touching a logger first forces slf4j to
    * finish binding its provider — calling `getILoggerFactory` cold can return
    * a transient `SubstituteLoggerFactory` mid-initialization. `None` when
    * logback isn't the active backend, so file logging is skipped rather than
    * crashing the flow.
    */
  private def loggerContext(): Option[LoggerContext] =
    val _ = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)
    LoggerFactory.getILoggerFactory match
      case ctx: LoggerContext => Some(ctx)
      case _                  => None
