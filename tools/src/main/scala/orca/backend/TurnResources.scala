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
      catch case NonFatal(e) => log.debug("turn resource release failed", e)

  def useCloseable[T <: AutoCloseable](acquire: => T)(using ResourceScope): T =
    use(acquire)(_.close())

  /** A temp file removed when the turn ends. */
  def tempFile(acquire: => os.Path)(using ResourceScope): os.Path =
    use(acquire)(path => os.remove(path): Unit)
