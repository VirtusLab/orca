package orca.backend

import ox.{Ox, supervised}
import ox.channels.BufferCapacity

/** Test scaffold for backend constructors that require `using Ox,
  * BufferCapacity` (claude and codex both stand up an MCP server on the Ox
  * scope's lifetime, which forces those constraints onto every test that
  * instantiates them). Opens a `supervised:` scope, provides a default
  * `BufferCapacity`, invokes the supplied factory, and yields the resulting
  * backend to the test body.
  *
  * Per-suite `withBackend` wrappers stay readable as one-liners around this
  * helper; the shared scope ensures the magic capacity constant lives in one
  * place.
  */
private[orca] object SupervisedBackend:

  /** Default buffer capacity used by every backend test. Sized for the tightest
    * tests (a couple of in-flight events) without being so small it
    * back-pressures load-bearing scenarios.
    */
  private val DefaultBufferCapacity: BufferCapacity = BufferCapacity(8)

  def using[B, T](make: (Ox, BufferCapacity) ?=> B)(body: B => T): T =
    supervised:
      given BufferCapacity = DefaultBufferCapacity
      body(make)
