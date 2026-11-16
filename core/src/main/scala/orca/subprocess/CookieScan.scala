package orca.subprocess

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.StreamConverters.*
import scala.util.control.NonFatal

/** The `/proc` scan behind [[PipedCliProcess.survivors]]. Linux only: no
  * equivalent has been verified on macOS, so elsewhere it finds nothing.
  */
private[orca] object CookieScan:

  private val ProcFs: Path = Path.of("/proc")

  /** `environ`'s entry separator. */
  private val Nul: Char = 0

  /** Whether this machine exposes readable per-process environments. */
  val supported: Boolean = Files.isDirectory(ProcFs)

  /** Live processes other than this JVM whose environment carries `cookie`. */
  def carrying(cookie: EnvCookie): List[ProcessHandle] =
    if !supported then Nil
    else
      val entry = s"${EnvCookie.VarName}=${cookie.value}"
      val self = ProcessHandle.current().pid
      ProcessHandle
        .allProcesses()
        .toScala(List)
        .filter(h => h.pid != self && carriesEntry(h.pid, entry))

  /** Compares whole NUL-delimited entries, so a variable whose name merely ends
    * with ours cannot match. A process that exited mid-scan and one owned by
    * another user both read as `false` — the file is gone or unreadable, which
    * is all this needs to decide.
    */
  private def carriesEntry(pid: Long, entry: String): Boolean =
    try
      new String(
        Files.readAllBytes(ProcFs.resolve(pid.toString).resolve("environ")),
        UTF_8
      ).split(Nul).contains(entry)
    catch case NonFatal(_) => false
