package orca.subprocess

/** Subprocess invocation helpers that **guarantee captured stderr**.
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
  * `CliRunner`, which now wraps it). Direct `os.proc(...).call(...)` in
  * production tool code is a leak — the tool's terminal output becomes
  * invisible to the StatusBar's clear-line discipline and the user sees torn
  * frames.*
  *
  * Subprocess errors aren't dropped: stderr is captured into `result.err`,
  * which the caller surfaces in error messages (see
  * [[orca.tools.git.OsGitTool]]'s `git` helper).
  */
private[orca] object QuietProc:

  /** Run `args` to completion. stdout + stderr are captured into the returned
    * [[os.CommandResult]]; `check = false` means non-zero exits don't throw —
    * the caller inspects `exitCode` / `err.text()` and decides how to react.
    * Mirrors the os-lib `call` shape so migration from `os.proc(...).call(...)`
    * is mechanical.
    */
  def call(
      args: Seq[String],
      cwd: os.Path = null,
      env: Map[String, String] = null,
      stdin: os.ProcessInput = os.Pipe
  ): os.CommandResult =
    os.proc(args)
      .call(
        cwd = cwd,
        env = env,
        stdin = stdin,
        stdout = os.Pipe,
        stderr = os.Pipe,
        check = false
      )
