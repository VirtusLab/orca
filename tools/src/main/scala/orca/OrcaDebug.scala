package orca

/** One place that knows which `ORCA_*` debug environment variables exist. The
  * flags are read once at object initialization (env vars don't change
  * mid-process), so the booleans are safe to pin in cold paths.
  *
  * Display-side env vars (`NO_COLOR`, `ORCA_NO_ANIMATION`, `CI`) live in
  * `orca.runner.terminal.TerminalInteraction`'s companion since they're
  * terminal-rendering specific; this object covers only the debug/diagnostic
  * switches that more than one module reads.
  */
object OrcaDebug:

  /** `ORCA_DEBUG=1` — print full JVM stack traces on errors that the stage
    * machinery would otherwise format. Read by [[orca.flow]].
    */
  val enabled: Boolean = sys.env.get("ORCA_DEBUG").contains("1")

  /** `ORCA_DEBUG_STREAM=1` — dump every inbound NDJSON / JSONL line from the
    * agent subprocess to the parent's stderr before parsing. Read by the
    * conversation drivers.
    */
  val streamTrace: Boolean = sys.env.get("ORCA_DEBUG_STREAM").contains("1")
