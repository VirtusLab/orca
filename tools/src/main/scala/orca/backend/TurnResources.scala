package orca.backend

import orca.safe.Logger
import orca.safe.concurrency.useInScope
import orca.safe.prelude.NonFatal
import ox.ResourceScope

/** Registers what one turn allocates — temp files, MCP bindings, config edits —
  * with the turn's scope, released in reverse order once the scope has joined
  * its forks. Temp files come from `FileSystem.tempFile`/`tempDir`, which
  * register with the scope themselves.
  *
  * A failing release is logged and swallowed: by then the turn has its outcome,
  * and a teardown error must not turn a finished turn into a failed (and
  * retried) one.
  */
private[backend] object TurnResources:

  private val log = Logger("orca.backend")

  def use[T](acquire: => T)(release: T => Unit)(using ResourceScope): T =
    useInScope(acquire): resource =>
      try release(resource)
      catch case NonFatal(e) => log.warn("turn resource release failed", e)

  def useCloseable[T <: AutoCloseable^](acquire: => T)(using ResourceScope): T =
    use(acquire)(_.close())
