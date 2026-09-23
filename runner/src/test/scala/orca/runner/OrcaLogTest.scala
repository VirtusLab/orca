package orca.runner

import orca.{AttemptId, OrcaDir}
import orca.testkit.TempDirs
import org.slf4j.LoggerFactory

import java.time.Instant

class OrcaLogTest extends munit.FunSuite:

  test("captures orca DEBUG logs to the trace file; finish is idempotent"):
    val workDir = TempDirs.dir()
    val id = AttemptId(Instant.now(), 1)
    val orcaLog = OrcaLog.start(workDir, id)
    // `orca.*` is DEBUG (logback.xml), so this lands in the trace file even
    // though it's below the console's WARN threshold.
    LoggerFactory.getLogger("orca.flow").debug("trace-marker-{}", "abc")

    orcaLog.finish()

    val file = OrcaDir.traceLogPath(workDir, id)
    assertEquals(orcaLog.file, Some(file))
    val onDisk = os.read(file)
    assert(onDisk.contains("trace-marker-abc"), onDisk)

    // Idempotent: a second finish neither throws nor double-detaches.
    orcaLog.finish()

  /** Pruning deletes a rolled part only when `OrcaDir.attemptIdOf` recognises
    * the name logback gives it.
    */
  test("a rolled trace part is named so pruning maps it to its attempt"):
    val workDir = TempDirs.dir()
    val id = AttemptId(Instant.now(), 1)
    val orcaLog = OrcaLog.start(workDir, id)
    val log = LoggerFactory.getLogger("orca.flow")
    val line = "x" * 1024
    for _ <- 1 to 5 * 1024 do log.debug(line) // ~5 MB: past the 4 MB roll limit
    // The size is measured at most once per check increment (200 ms).
    Thread.sleep(300)
    log.debug("after the limit")
    orcaLog.finish()

    val others = os
      .list(OrcaDir.attemptsPath(workDir))
      .filterNot(orcaLog.file.contains)
      .toList
    assertEquals(others.map(OrcaDir.attemptIdOf), List(Some(id)))
    assert(others.head.last.endsWith(OrcaDir.RolledTraceLogSuffix), others)
