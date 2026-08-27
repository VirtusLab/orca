package orca.runner

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.FileAppender
import ch.qos.logback.core.rolling.{
  FixedWindowRollingPolicy,
  RollingFileAppender,
  SizeBasedTriggeringPolicy
}
import ch.qos.logback.core.util.{Duration, FileSize}
import org.slf4j.{Logger, LoggerFactory}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.NonFatal

/** Per-run execution-trace log.
  *
  * [[start]] creates a fresh temp file and attaches a DEBUG-level logback
  * `RollingFileAppender` to the `orca` logger, made non-additive — so the whole
  * `orca.*` tree lands in the file and never propagates to the root console
  * appender. Framework chatter (netty/tapir/…) is on its own loggers and still
  * reaches the console's WARN appender. The trace rolls at
  * [[OrcaLog.MaxTraceFileSize]], which bounds what one run can write.
  *
  * The file is NOT deleted on exit, so it can be inspected after the run. If
  * logback isn't the active slf4j backend, or the temp file can't be created,
  * file logging is skipped (best-effort) and [[file]] is `None`.
  *
  * The trace file carries full diagnostics (every level, including stacks). The
  * console shows only high-level lines: framework WARN-and-above (still
  * additive), and orca's own code only through deliberate `[orca]`-prefixed
  * `System.err` lines.
  */
private[orca] final class OrcaLog private (
    val file: Option[os.Path],
    appender: Option[FileAppender[ILoggingEvent]],
    target: Option[ch.qos.logback.classic.Logger]
):
  private val finished = new AtomicBoolean(false)

  /** Detach and stop the per-run file appender and restore the `orca` logger to
    * additive — so a later run, or another test in a shared JVM, logs normally
    * again. The trace is left on disk. Idempotent.
    */
  def finish(): Unit =
    if finished.compareAndSet(false, true) then
      appender.foreach(_.stop())
      for a <- appender; t <- target do
        t.detachAppender(a)
        t.setAdditive(true)

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

        val appender = new RollingFileAppender[ILoggingEvent]
        appender.setContext(ctx)
        appender.setName("orca-run-trace")
        appender.setFile(file.toString)
        // No `setAppend(false)`: a rolling appender only appends, and the file
        // `os.temp` just created is empty anyway.
        appender.setEncoder(encoder)
        capSize(ctx, appender, file)
        appender.start()

        val orcaLogger = ctx.getLogger("orca")
        orcaLogger.addAppender(appender)
        orcaLogger.setAdditive(false) // orca.* → file only, never the console
        new OrcaLog(Some(file), Some(appender), Some(orcaLogger))
      case _ =>
        // No temp file or logback isn't active: skip file logging.
        new OrcaLog(None, None, None)

  /** Max size of one trace file before it rolls. Every review, fix and picker
    * prompt is traced, so a real run writes megabytes; the file lives in the
    * system temp dir, where an unbounded run fills a tmpfs and every later orca
    * command fails on a full disk.
    */
  private val MaxTraceFileSize: String = "4MB"

  /** Roll the trace at [[MaxTraceFileSize]], keeping one earlier part beside
    * it, so a run's trace costs at most twice that. The tail is what a
    * post-mortem reads; a run long enough to roll twice loses its start.
    *
    * The rolled part sits next to `file` as `<name>.1.log`, since the pattern
    * only replaces the suffix `os.temp` gave it.
    */
  private def capSize(
      ctx: LoggerContext,
      appender: RollingFileAppender[ILoggingEvent],
      file: os.Path
  ): Unit =
    val rolling = new FixedWindowRollingPolicy
    rolling.setContext(ctx)
    rolling.setParent(appender)
    rolling.setFileNamePattern(s"${file.toString.stripSuffix(".log")}.%i.log")
    rolling.setMinIndex(1)
    rolling.setMaxIndex(1)
    rolling.start()

    val triggering = new SizeBasedTriggeringPolicy[ILoggingEvent]
    triggering.setContext(ctx)
    triggering.setMaxFileSize(FileSize.valueOf(MaxTraceFileSize))
    // Logback measures the file at most once a minute by default, which a run
    // writing whole prompts blows through between two measurements. The file
    // can still overshoot by whatever is written inside one interval, so keep
    // it short — the measurement is one `stat`.
    triggering.setCheckIncrement(Duration.buildByMilliseconds(200))
    triggering.start()

    appender.setRollingPolicy(rolling)
    appender.setTriggeringPolicy(triggering)

  /** The bound logback `LoggerContext`. Touching a logger first forces slf4j to
    * finish binding its provider — calling `getILoggerFactory` cold can return
    * a transient `SubstituteLoggerFactory` mid-initialization. `None` when
    * logback isn't the active backend.
    */
  private def loggerContext(): Option[LoggerContext] =
    val _ = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)
    LoggerFactory.getILoggerFactory match
      case ctx: LoggerContext => Some(ctx)
      case _                  => None
