package orca.backend

import ox.{Ox, supervised}

/** Test scaffold for backend constructors that require `using Ox` (opencode
  * pins a shared `serve` process to the Ox scope's lifetime at construction,
  * which forces that constraint onto every test that instantiates it). Opens a
  * `supervised:` scope, invokes the supplied factory, and yields the resulting
  * backend to the test body.
  *
  * Per-suite `withBackend` wrappers stay readable as one-liners around this
  * helper; the shared scope is also what interactive backends need to call
  * `runInteractive(...)(using Ox)`.
  */
private[orca] object SupervisedBackend:

  /** `body` is a context function so the scope's `Ox` is visible inside it —
    * interactive backends need it to call `runInteractive(...)(using Ox)`.
    * Autonomous bodies simply ignore the given.
    */
  def using[B, T](make: Ox ?=> B)(body: Ox ?=> B => T): T =
    supervised:
      body(make)
