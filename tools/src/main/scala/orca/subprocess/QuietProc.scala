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

  /** Run `args` to completion, keeping at most `maxOutBytes` of stdout: the
    * rest is drained and dropped, so the exit code and stderr are still the
    * ones [[call]] would report and the child never blocks on a full pipe.
    *
    * For commands whose output size is set by what they are reading rather than
    * by their arguments — [[call]] would put all of it on the heap as a
    * `String` before the caller could react.
    */
  def callCapped(
      args: Seq[String],
      maxOutBytes: Int,
      cwd: os.Path = os.pwd,
      env: Map[String, String] = Map.empty
  ): CappedResult =
    log.debug("exec: {}", args.mkString(" "))
    val kept = new java.io.ByteArrayOutputStream()
    var produced = 0L
    // os-lib runs this on a pump thread of its own and joins it before `call`
    // returns; that join is what publishes `kept` and `produced` here.
    val capture = os.ProcessOutput: (buf, n) =>
      produced += n
      val room = maxOutBytes - kept.size()
      if room > 0 then kept.write(buf, 0, math.min(n, room))
    val result = os
      .proc(args)
      .call(
        cwd = cwd,
        env = env,
        stdin = os.Pipe,
        stdout = capture,
        stderr = os.Pipe,
        check = false
      )
    CappedResult(
      exitCode = result.exitCode,
      out = String(kept.toByteArray, java.nio.charset.StandardCharsets.UTF_8),
      truncated = produced > maxOutBytes,
      err = result.err.text()
    )

/** Outcome of [[QuietProc.callCapped]]. `out` is a prefix of what the child
  * wrote whenever `truncated` — and, being a prefix of bytes rather than of
  * characters, can end in a replacement character where the cut split one.
  */
private[orca] case class CappedResult(
    exitCode: Int,
    out: String,
    truncated: Boolean,
    err: String
)
