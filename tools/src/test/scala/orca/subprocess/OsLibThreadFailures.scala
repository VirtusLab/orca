package orca.subprocess

import ox.discard

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.given

/** Names of os-lib's subprocess shutdown-hook threads that died of an uncaught
  * exception while `body` ran. Swaps the JVM-wide default handler for the
  * duration.
  */
object OsLibThreadFailures:

  private val ThreadPrefix = "subprocess-shutdown-hook"

  def during(body: => Unit): List[String] =
    val failed = ConcurrentLinkedQueue[String]()
    val previous = Thread.getDefaultUncaughtExceptionHandler
    Thread.setDefaultUncaughtExceptionHandler: (thread, error) =>
      if thread.getName.startsWith(ThreadPrefix) then
        failed.add(thread.getName).discard
      else if previous != null then previous.uncaughtException(thread, error)
    try
      body
      // Bounded: other suites' live processes have monitor threads too.
      liveOsLibThreads().foreach(_.join(2000))
    finally Thread.setDefaultUncaughtExceptionHandler(previous)
    failed.asScala.toList

  private def liveOsLibThreads(): List[Thread] =
    Thread.getAllStackTraces.keySet.asScala.toList
      .filter(_.getName.startsWith(ThreadPrefix))
