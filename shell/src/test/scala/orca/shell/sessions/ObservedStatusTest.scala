package orca.shell.sessions

import java.time.Instant

class ObservedStatusTest extends munit.FunSuite:

  private def attempt(pid: Long, startedAt: Instant) =
    ManifestFixtures.manifest(
      pid = pid,
      startedAt = startedAt.toString,
      sessions = Nil
    )

  test(
    "processAlive: a process started within the slack after the attempt is alive"
  ):
    val self = ProcessHandle.current()
    val startedAt = self.info().startInstant().get().minusSeconds(30)
    assert(ObservedStatus.processAlive(attempt(self.pid(), startedAt)))

  test("processAlive: a live process started after the attempt reused its pid"):
    val self = ProcessHandle.current()
    assert(!ObservedStatus.processAlive(attempt(self.pid(), Instant.EPOCH)))

  test("processAlive: an exited process is not alive"):
    val exited = os.proc("true").spawn()
    assertEquals(exited.wrapped.waitFor(), 0)
    assert(
      !ObservedStatus.processAlive(attempt(exited.wrapped.pid(), Instant.now()))
    )
