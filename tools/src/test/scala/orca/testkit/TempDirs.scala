package orca.testkit

import java.util.concurrent.ConcurrentLinkedQueue

/** Shared temp-dir cleanup for tests.
  *
  * `os.temp.dir(..., deleteOnExit = true)` relies on JVM `File.deleteOnExit`,
  * which only removes an *empty* directory at shutdown and silently no-ops on a
  * non-empty tree — so every fixture that gets files written into it leaks.
  * Instead, `register`/`dir` track every temp root and recursively remove it
  * (`os.remove.all`, which handles non-empty trees) via a single JVM shutdown
  * hook.
  *
  * Cleanup timing depends on the run mode: a one-shot `sbt test` exits its JVM
  * when done, so the hook sweeps every fixture per invocation. A long-lived
  * `sbt ~test` on an unforked module accumulates dirs until the session exits.
  */
object TempDirs:
  private val roots = new ConcurrentLinkedQueue[os.Path]()

  locally:
    Runtime.getRuntime.addShutdownHook(
      new Thread(() =>
        roots.forEach(p => { val _ = scala.util.Try(os.remove.all(p)) })
      )
    )

  /** Registers `root` for recursive removal at JVM shutdown; returns `root`
    * unchanged so [[dir]] can wrap it in place.
    */
  private def register(root: os.Path): os.Path =
    roots.add(root)
    root

  /** Fresh temp dir, pre-registered for cleanup at JVM shutdown. Drop-in
    * replacement for a bare `os.temp.dir()` test workDir.
    */
  def dir(prefix: String = "orca-test-"): os.Path =
    register(os.temp.dir(prefix = prefix, deleteOnExit = false))
