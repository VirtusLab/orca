package orca.backend

import ox.{Ox, supervised}

/** Test scaffold for backend constructors that require `using Ox` (opencode
  * pins a shared `serve` process to the Ox scope's lifetime). Opens a
  * `supervised:` scope, invokes the factory, yields the backend to the test
  * body, and `close()`s it in the body's `finally` — before the scope joins its
  * forks. That ordering is load-bearing: opencode's server-drain forks block on
  * non-interruptible reads that only the close-triggered process kill unblocks,
  * so a scope exit without the close deadlocks the join.
  */
private[orca] object SupervisedBackend:

  /** `body` receives the scope's `Ox` so interactive backends can call
    * `runInteractive(...)(using Ox)`.
    */
  def using[B <: AgentBackend[?], T](make: Ox ?=> B)(body: Ox ?=> B => T): T =
    supervised:
      val backend = make
      try body(backend)
      finally backend.close()
