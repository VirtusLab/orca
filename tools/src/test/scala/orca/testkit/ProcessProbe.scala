package orca.testkit

/** Liveness probes for a PID a test spawned, for asserting that teardown
  * actually reaped a process rather than sleeping and hoping.
  */
object ProcessProbe:

  def alive(pid: Long): Boolean =
    val handle = ProcessHandle.of(pid)
    handle.isPresent && handle.get.isAlive

  /** Polls `condition` until it holds, up to `timeoutMillis`; returns whether
    * it ever did. Process teardown is asynchronous — a signal is delivered
    * before the process is reaped — so a same-instant check would be flaky in
    * either direction.
    */
  def awaitTrue(condition: => Boolean, timeoutMillis: Long = 5000): Boolean =
    val deadline = System.currentTimeMillis + timeoutMillis
    while !condition && System.currentTimeMillis < deadline do Thread.sleep(20)
    condition

  def awaitDead(pid: Long, timeoutMillis: Long = 5000): Boolean =
    awaitTrue(!alive(pid), timeoutMillis)
