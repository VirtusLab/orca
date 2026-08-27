package orca.runner.terminal

/** Which tool names the event log renders as the constant `⏺ read` line instead
  * of naming the file each call touched.
  *
  * Reading is what an agent does most — about half of a run's log lines — and
  * each line says only that it is still working, which the status row already
  * says. Rendering them all identically lets [[TerminalOutputState]]'s
  * repeat-collapse fold a run of them into one line plus `⎿ ×N`. The emitting
  * agent's name is dropped for the same reason: the reviewer fan-out is where
  * reads are densest, and a `name: ` prefix would make every line distinct
  * again and collapse nothing. The filename and the name are not lost:
  * `LoggingListener` records every call at DEBUG in the trace file whose path
  * the run banner prints.
  *
  * Display-only and best-effort. An unrecognised name is treated as mutating
  * and printed in full — a write shown as a read is the harmful direction, and
  * backends add tools without telling orca. codex is deliberately absent: its
  * reads run through `bash`, which also writes.
  */
private[terminal] object ReadOnlyTools:

  /** The line a read-only call renders as, in place of its own name. */
  val DisplayName: String = "read"

  /** Lower-cased so one entry covers claude's `Read` and opencode's `read`.
    * Grouped by the backend that emits them (several are shared).
    */
  private val Names: Set[String] = Set(
    // claude
    "read",
    "grep",
    "glob",
    // pi
    "find",
    "ls",
    // opencode
    "list",
    // gemini
    "read_file",
    "read_many_files",
    "search_file_content",
    "list_directory"
  )

  def contains(toolName: String): Boolean =
    Names.contains(toolName.toLowerCase)
