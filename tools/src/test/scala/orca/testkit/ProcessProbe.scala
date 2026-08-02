package orca.testkit

/** Liveness probes for a PID a test spawned, for asserting that teardown
  * actually reaped a process rather than sleeping and hoping.
  */
object ProcessProbe:

  def alive(pid: Long): Boolean =
    val handle = ProcessHandle.of(pid)
    handle.isPresent && handle.get.isAlive

  /** Polls until `pid` is gone, up to `timeoutMillis`; returns whether it died.
    * A kill is asynchronous — the signal is delivered before the process is
    * reaped — so a same-instant `alive` check would be flaky in either
    * direction.
    */
  def awaitDead(pid: Long, timeoutMillis: Long = 5000): Boolean =
    val deadline = System.currentTimeMillis + timeoutMillis
    while alive(pid) && System.currentTimeMillis < deadline do Thread.sleep(20)
    !alive(pid)
