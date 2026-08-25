package orca.subprocess

import scala.util.control.NonFatal

/** Whether a specific standard stream of this JVM process is attached to a real
  * terminal, probed independently per stream. `System.console() != null` (the
  * JDK's only built-in check) conflates the two: it is `null` the moment EITHER
  * stdin or stdout is redirected, so it cannot tell "stdin is a terminal but
  * stdout is piped to a file" from "neither is" — wrong for a caller that only
  * cares about one side (`orca run`'s task-reading only cares about stdin;
  * `orca view`'s highlighting only cares about stdout; the runner's dirty-tree
  * prompt asks on stderr and reads stdin, so it cares about both of those).
  *
  * Spawns POSIX `test -t <fd>`, inheriting only the probed stream from this
  * process (the others are piped and discarded) — its exit code (0 ⇒ a tty)
  * reflects that one stream's real attachment, unlike `System.console()`. A
  * failure to even spawn `test` (missing binary, non-POSIX host) is treated as
  * "not a tty" rather than thrown; every platform this ships on has it
  * (coreutils/busybox).
  */
private[orca] object TtyProbe:

  def stdin(): Boolean = probe(fd = 0)

  def stdout(): Boolean = probe(fd = 1)

  def stderr(): Boolean = probe(fd = 2)

  private def probe(fd: Int): Boolean =
    try
      os.proc("test", "-t", fd.toString)
        .call(
          stdin = if fd == 0 then os.Inherit else os.Pipe,
          stdout = if fd == 1 then os.Inherit else os.Pipe,
          stderr = if fd == 2 then os.Inherit else os.Pipe,
          check = false
        )
        .exitCode == 0
    catch case NonFatal(_) => false
