package orca.subprocess

import ox.discard

/** `os.proc(args).spawn(...)`, with the process destroyed on JVM exit like
  * os-lib's `destroyOnExit`, but a failed start (e.g. a program not on PATH)
  * only throws to the caller.
  *
  * os-lib's `destroyOnExit` registers its shutdown hook and starts a monitor
  * thread before starting the process. When the start fails, the monitor and,
  * at JVM exit, the hook each retry the start and report the failure as an
  * uncaught exception. Here the hook is registered only once the process runs.
  */
private[subprocess] object Spawn:

  def apply(
      args: Seq[String],
      cwd: os.Path,
      env: Map[String, String],
      stdin: os.ProcessInput,
      stdout: os.ProcessOutput,
      stderr: os.ProcessOutput
  ): os.SubProcess =
    val sub = os
      .proc(args)
      .spawn(
        cwd = cwd,
        env = env,
        stdin = stdin,
        stdout = stdout,
        stderr = stderr,
        destroyOnExit = false
      )
    destroyOnJvmExit(sub)
    sub

  private def destroyOnJvmExit(sub: os.SubProcess): Unit =
    val hook = Thread: () =>
      sub.destroy(sub.shutdownGracePeriod, async = false, recursive = true)
    try Runtime.getRuntime.addShutdownHook(hook)
    catch
      // The JVM is already shutting down. os-lib refuses the spawn then too.
      case e: IllegalStateException =>
        sub.wrapped.destroyForcibly().discard
        throw e
    sub.wrapped.onExit().thenRun(() => removeHook(hook)).discard

  private def removeHook(hook: Thread): Unit =
    // Throws once the JVM is shutting down, when the hook runs anyway.
    try Runtime.getRuntime.removeShutdownHook(hook).discard
    catch case _: IllegalStateException => ()
