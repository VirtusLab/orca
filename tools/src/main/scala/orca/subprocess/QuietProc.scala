package orca.subprocess

/** The single quiet-shell-out implementation: subprocess invocation that
  * **guarantees captured stderr**.
  *
  * Why this exists: os-lib 0.11.x defaults `os.proc(...).call(...)`'s `stderr`
  * to `os.Inherit`. A flow tool that uses `os.proc` directly therefore lets the
  * child write its stderr straight to the parent's terminal — bypassing the
  * renderer's [[orca.runner.terminal.StatusBar]] and producing visible
  * artifacts (a stray "Switched to a new branch" appearing on the same physical
  * row as the spinner glyph, mid-redraw).
  *
  * The rule, encoded as a code-level helper rather than a comment:
  *
  * *Any tool that shells out from a flow goes through `QuietProc.call` (or a
  * `CliRunner`, which — via [[OsProcCliRunner]] — delegates to it). Direct
  * `os.proc(...).call(...)` in production tool code is a leak — the tool's
  * terminal output becomes invisible to the StatusBar's clear-line discipline
  * and the user sees torn frames.*
  *
  * Subprocess errors aren't dropped: stderr is captured into `result.err`,
  * which the caller surfaces in error messages (see [[orca.tools.OsGitTool]]'s
  * `git` helper).
  */
private[orca] object QuietProc:

  private val log = org.slf4j.LoggerFactory.getLogger("orca.proc")

  /** Run `args` to completion. stdout + stderr are captured into the returned
    * [[os.CommandResult]]; `check = false` means non-zero exits don't throw —
    * the caller inspects `exitCode` / `err.text()` and decides how to react.
    * Mirrors the os-lib `call` shape so migration from `os.proc(...).call(...)`
    * is mechanical.
    *
    * `cwd` and `env` default to concrete values (the current directory / an
    * empty map merged onto the inherited environment) rather than os-lib's own
    * `null`-means-inherit convention, per this codebase's no-null style; the
    * two are behaviourally identical (os-lib treats a `null` `cwd` as `os.pwd`
    * and an empty `env` map contributes no overrides on top of the inherited
    * environment either way).
    */
  def call(
      args: Seq[String],
      cwd: os.Path = os.pwd,
      env: Map[String, String] = Map.empty,
      stdin: os.ProcessInput = os.Pipe
  ): os.CommandResult =
    log.debug("exec: {}", args.mkString(" "))
    os.proc(args)
      .call(
        cwd = cwd,
        env = env,
        stdin = stdin,
        stdout = os.Pipe,
        stderr = os.Pipe,
        check = false
      )
