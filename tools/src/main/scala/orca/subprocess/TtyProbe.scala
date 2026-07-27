package orca.subprocess

import scala.util.control.NonFatal

/** Whether a specific standard stream of this JVM process is attached to a real
  * terminal, probed independently per stream. `System.console() != null` (the
  * JDK's only built-in check) conflates the two: it is `null` the moment EITHER
  * stdin or stdout is redirected, so it cannot tell "stdin is a terminal but
  * stdout is piped to a file" from "neither is" — wrong for a caller that only
  * cares about one side (`orca run`'s task-reading only cares about stdin;
  * `orca view`'s highlighting only cares about stdout).
  *
  * Spawns POSIX `test -t <fd>`, inheriting only the probed stream from this
  * process (the other one is piped and discarded) — its exit code (0 ⇒ a tty)
  * reflects that one stream's real attachment, unlike `System.console()`. A
  * failure to even spawn `test` (missing binary, non-POSIX host) is treated as
  * "not a tty" rather than thrown; every platform this ships on has it
  * (coreutils/busybox).
  */
private[orca] object TtyProbe:

  def stdin(): Boolean = probe(fd = 0, probeStdin = true)

  def stdout(): Boolean = probe(fd = 1, probeStdin = false)

  private def probe(fd: Int, probeStdin: Boolean): Boolean =
    try
      os.proc("test", "-t", fd.toString)
        .call(
          stdin = if probeStdin then os.Inherit else os.Pipe,
          stdout = if probeStdin then os.Pipe else os.Inherit,
          stderr = os.Pipe,
          check = false
        )
        .exitCode == 0
    catch case NonFatal(_) => false
