package orca.runner

import org.slf4j.LoggerFactory

import java.io.{ByteArrayOutputStream, PrintStream}

class OrcaLogTest extends munit.FunSuite:

  test(
    "captures orca DEBUG logs to the trace file; finish dumps + is idempotent"
  ):
    val orcaLog = OrcaLog.start()
    // `orca.*` is DEBUG (logback.xml), so this lands in the per-run file even
    // though it's below the console's WARN threshold.
    LoggerFactory.getLogger("orca.flow").debug("trace-marker-{}", "abc")

    val captured = new ByteArrayOutputStream()
    orcaLog.finish(new PrintStream(captured, true, "UTF-8"), dump = true)

    assert(os.exists(orcaLog.file), "trace file should exist")
    val onDisk = os.read(orcaLog.file)
    assert(onDisk.contains("trace-marker-abc"), onDisk)
    // dump=true prints the file (and its path) to the supplied stream
    val printed = captured.toString("UTF-8")
    assert(printed.contains("trace-marker-abc"), printed)
    assert(printed.contains(orcaLog.file.toString), printed)

    // Idempotent: a second finish neither throws nor re-dumps.
    val second = new ByteArrayOutputStream()
    orcaLog.finish(new PrintStream(second, true, "UTF-8"), dump = true)
    assertEquals(second.toString("UTF-8"), "")
