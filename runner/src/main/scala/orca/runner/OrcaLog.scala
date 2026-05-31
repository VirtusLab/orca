package orca.runner

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.FileAppender
import org.slf4j.{Logger, LoggerFactory}

import java.nio.charset.StandardCharsets.UTF_8

/** Per-run execution-trace log.
  *
  * [[start]] creates a fresh temp file and attaches a DEBUG-level logback
  * `FileAppender` to the root logger, so everything orca logs during the run
  * lands there: events (via [[LoggingListener]]) and subprocess invocations
  * (logger `orca.proc`). The console stays quiet — `logback.xml` filters STDERR
  * to WARN+ — so the run's detail lives in the file rather than the terminal;
  * its path is shown once at startup by the banner.
  *
  * The file is intentionally NOT deleted on exit, so it can be inspected after
  * the run. If logback isn't the active slf4j backend, file logging is skipped
  * (the appender is absent) rather than failing the flow — the file is then
  * created but stays empty.
  */
private[orca] final class OrcaLog private (
    val file: os.Path,
    appender: Option[FileAppender[ILoggingEvent]],
    rootLogger: Option[ch.qos.logback.classic.Logger]
):
  private var finished = false

  /** Detach and stop the per-run file appender. The trace is left on disk for
    * inspection — its path is shown by the startup banner, so nothing is
    * echoed to the console. Idempotent — safe to call from both the error path
    * (before `System.exit`) and the success/finally path.
    */
  def finish(): Unit =
    if !finished then
      finished = true
      appender.foreach(_.stop())
      for a <- appender; r <- rootLogger do r.detachAppender(a)

private[orca] object OrcaLog:
  /** Attach a fresh per-run DEBUG file appender and return the handle. Must be
    * called before the flow does any logging so the whole run is captured.
    */
  def start(): OrcaLog =
    val file =
      os.temp(prefix = "orca-", suffix = ".log", deleteOnExit = false)
    loggerContext() match
      case Some(ctx) =>
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

        val root = ctx.getLogger(Logger.ROOT_LOGGER_NAME)
        root.addAppender(appender)
        new OrcaLog(file, Some(appender), Some(root))
      case None =>
        new OrcaLog(file, None, None)

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
