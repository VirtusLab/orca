package orca.subprocess

/** Subprocess invocation that **guarantees captured stderr**.
  *
  * os-lib defaults `os.proc(...).call(...)`'s `stderr` to `os.Inherit`, which
  * lets the child write straight to the parent's terminal — bypassing the
  * renderer's [[orca.runner.terminal.StatusBar]] and producing torn frames
  * (e.g. a stray "Switched to a new branch" on the spinner's row mid-redraw).
  * So any tool that shells out from a flow goes through `QuietProc.call` (or a
  * `CliRunner`, which delegates here via [[OsProcCliRunner]]); a direct
  * `os.proc(...).call(...)` in production tool code is a leak.
  *
  * Captured stderr lands in `result.err`, which the caller surfaces in error
  * messages (see [[orca.tools.OsGitTool]]'s `git` helper).
  */
private[orca] object QuietProc:

  private val log = org.slf4j.LoggerFactory.getLogger("orca.proc")

  /** Run `args` to completion. stdout + stderr are captured into the returned
    * [[os.CommandResult]]; `check = false` means non-zero exits don't throw —
    * the caller inspects `exitCode` / `err.text()` and decides how to react.
    *
    * `cwd` and `env` default to concrete values (current directory / an empty
    * map merged onto the inherited environment) rather than os-lib's
    * `null`-means-inherit, per this codebase's no-null style; behaviourally
    * identical either way.
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
