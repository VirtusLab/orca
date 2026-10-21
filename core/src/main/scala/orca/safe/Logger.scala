package orca.safe

import language.experimental.captureChecking

import caps.assumeSafe

/** An slf4j logger safe code may create without a capability: logging is
  * deliberately left untracked.
  */
@assumeSafe
final class Logger(name: String):
  private val underlying = org.slf4j.LoggerFactory.getLogger(name)

  def debug(msg: => String): Unit =
    if underlying.isDebugEnabled then underlying.debug(msg)
  def debug(msg: => String, t: Throwable): Unit =
    if underlying.isDebugEnabled then underlying.debug(msg, t)
  def info(msg: String): Unit = underlying.info(msg)
  def warn(msg: String): Unit = underlying.warn(msg)
  def warn(msg: String, t: Throwable): Unit = underlying.warn(msg, t)
  def error(msg: String): Unit = underlying.error(msg)
  def error(msg: String, t: Throwable): Unit = underlying.error(msg, t)

@assumeSafe
object Logger:
  /** Writes `line` to the JVM's stderr, bypassing the logging configuration:
    * for opt-in debug dumps and for messages the user must see whatever the log
    * level.
    */
  def stderr(line: String): Unit = System.err.println(line)
