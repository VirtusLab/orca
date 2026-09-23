package orca.backend

import org.slf4j.LoggerFactory
import ox.ResourceScope

import scala.util.control.NonFatal

/** Registers what one turn allocates — temp files, MCP bindings, config edits —
  * with the turn's scope, released in reverse order once the scope has joined
  * its forks.
  *
  * A failing release is logged and swallowed: by then the turn has its outcome,
  * and a teardown error must not turn a finished turn into a failed (and
  * retried) one.
  */
private[orca] object TurnResources:

  private val log = LoggerFactory.getLogger("orca.backend")

  def use[T](acquire: => T)(release: T => Unit)(using ResourceScope): T =
    ox.useInScope(acquire): resource =>
      try release(resource)
      catch case NonFatal(e) => log.warn("turn resource release failed", e)

  def useCloseable[T <: AutoCloseable](acquire: => T)(using ResourceScope): T =
    use(acquire)(_.close())

  // Temp files and dirs skip `deleteOnExit`: the scope removes them, and each
  // registration would stay in the JVM's exit-hook list for the rest of a flow
  // that runs hundreds of turns.

  /** A temp file holding `contents`, removed when the turn ends. */
  def tempFile(contents: String, prefix: String, suffix: String)(using
      ResourceScope
  ): os.Path =
    use(
      os.temp(contents, prefix = prefix, suffix = suffix, deleteOnExit = false)
    )(path => os.remove(path): Unit)

  /** A temp directory, removed with its contents when the turn ends. */
  def tempDir(prefix: String)(using ResourceScope): os.Path =
    use(os.temp.dir(prefix = prefix, deleteOnExit = false))(os.remove.all(_))
