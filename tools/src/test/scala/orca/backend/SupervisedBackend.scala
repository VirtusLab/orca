package orca.backend

import ox.{Ox, supervised}

/** Test scaffold for backend constructors that require `using Ox` (opencode
  * pins a shared `serve` process to the Ox scope's lifetime): builds the
  * backend in a `supervised:` scope and yields it to the test body.
  */
private[orca] object SupervisedBackend:

  /** `body` receives the scope's `Ox` so tests can open a turn
    * (`OpenTurn.interactive`).
    */
  def using[B <: AgentBackend[?], T](make: Ox ?=> B)(body: Ox ?=> B => T): T =
    supervised(body(make))
