package orca.runner

import org.slf4j.LoggerFactory

class OrcaLogTest extends munit.FunSuite:

  test("captures orca DEBUG logs to the trace file; finish is idempotent"):
    val orcaLog = OrcaLog.start()
    // `orca.*` is DEBUG (logback.xml), so this lands in the per-run file even
    // though it's below the console's WARN threshold.
    LoggerFactory.getLogger("orca.flow").debug("trace-marker-{}", "abc")

    orcaLog.finish()

    val file =
      orcaLog.file.getOrElse(fail("trace file should have been created"))
    assert(os.exists(file), "trace file should exist")
    val onDisk = os.read(file)
    assert(onDisk.contains("trace-marker-abc"), onDisk)

    // Idempotent: a second finish neither throws nor double-detaches.
    orcaLog.finish()
