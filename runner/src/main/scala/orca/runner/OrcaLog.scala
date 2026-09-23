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
import orca.{AttemptId, OrcaDir}
import org.slf4j.{Logger, LoggerFactory}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicBoolean

/** Per-attempt execution-trace log.
  *
  * [[start]] attaches a DEBUG-level logback `RollingFileAppender` for the
  * attempt's trace file (`OrcaDir.traceLogPath`) to the `orca` logger, made
  * non-additive — so the whole `orca.*` tree lands in the file and never
  * propagates to the root console appender. Framework chatter (netty/tapir/…)
  * is on its own loggers and still reaches the console's WARN appender. The
  * trace rolls at [[OrcaLog.MaxTraceFileSize]], which bounds what one attempt
  * can write.
  *
  * The file is left on disk when the attempt ends, so it can be inspected
  * afterwards. If logback isn't the active slf4j backend, file logging is
  * skipped (best-effort) and [[file]] is `None`.
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

  /** Detach and stop the file appender and restore the `orca` logger to
    * additive — so a later attempt, or another test in a shared JVM, logs
    * normally again. The trace is left on disk. Idempotent.
    */
  def finish(): Unit =
    if finished.compareAndSet(false, true) then
      appender.foreach(_.stop())
      for a <- appender; t <- target do
        t.detachAppender(a)
        t.setAdditive(true)

private[orca] object OrcaLog:
  /** Attach a DEBUG file appender writing attempt `id`'s trace log under
    * `workDir` (`OrcaDir.traceLogPath`) and return the handle. Everything the
    * `orca` loggers emit before this call is not in the trace.
    */
  def start(workDir: os.Path, id: AttemptId): OrcaLog =
    val _ = OrcaDir.ensureAttempts(workDir)
    val file = OrcaDir.traceLogPath(workDir, id)
    loggerContext() match
      case Some(ctx) =>
        val encoder = new PatternLayoutEncoder
        encoder.setContext(ctx)
        encoder.setPattern("%d{HH:mm:ss.SSS} %-5level %logger{24} - %msg%n")
        encoder.setCharset(UTF_8)
        encoder.start()

        val appender = new RollingFileAppender[ILoggingEvent]
        appender.setContext(ctx)
        appender.setName("orca-attempt-trace")
        appender.setFile(file.toString)
        appender.setEncoder(encoder)
        capSize(ctx, appender, OrcaDir.traceLogRollPattern(workDir, id))
        appender.start()

        val orcaLogger = ctx.getLogger("orca")
        orcaLogger.addAppender(appender)
        orcaLogger.setAdditive(false) // orca.* → file only, never the console
        new OrcaLog(Some(file), Some(appender), Some(orcaLogger))
      case None => new OrcaLog(None, None, None)

  /** Max size of one trace file before it rolls. Every review, fix and picker
    * prompt is traced, so a real attempt writes megabytes; this bounds what one
    * attempt leaves in the cache, while attempt pruning bounds how many are
    * kept.
    */
  private val MaxTraceFileSize: String = "4MB"

  /** Roll the trace at [[MaxTraceFileSize]], keeping one earlier part beside
    * it, so an attempt's trace costs at most twice that. The tail is what a
    * post-mortem reads; an attempt long enough to roll twice loses its start.
    */
  private def capSize(
      ctx: LoggerContext,
      appender: RollingFileAppender[ILoggingEvent],
      rollPattern: String
  ): Unit =
    val rolling = new FixedWindowRollingPolicy
    rolling.setContext(ctx)
    rolling.setParent(appender)
    rolling.setFileNamePattern(rollPattern)
    rolling.setMinIndex(OrcaDir.TraceLogRollIndex)
    rolling.setMaxIndex(OrcaDir.TraceLogRollIndex)
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
