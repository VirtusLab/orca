package orca.testkit

import java.util.concurrent.ConcurrentLinkedQueue

/** Shared temp-dir cleanup for tests.
  *
  * `os.temp.dir(..., deleteOnExit = true)` relies on JVM `File.deleteOnExit`,
  * which only removes an *empty* directory at shutdown — it silently no-ops on
  * a non-empty tree. Every git-repo fixture (`GitRepo`) and every backend
  * workDir under test gets files written into it after creation, so on the
  * default `os.temp.dir()` behavior every one of those directories leaked
  * permanently. This is what filled an 8G tmpfs with ~35k orphaned fixture dirs
  * in a single day of test runs (see the Epic 12 blockquote in
  * `complexity-review-2.md`).
  *
  * `register`/`dir` track every temp root handed out and recursively remove it
  * (`os.remove.all`, which — unlike `deleteOnExit` — handles non-empty trees)
  * via a single JVM shutdown hook. Cleanup timing depends on how tests run:
  * only the `runner` module forks its tests (`Test / fork := true` in
  * `build.sbt`); the other modules run in-process in sbt's JVM. A one-shot `sbt
  * test` from a shell — the dominant CI/agent pattern per AGENTS.md — exits
  * that JVM when the command finishes, so the hook fires and every fixture dir
  * is swept per invocation either way. Under a long-lived interactive session
  * (`sbt ~test`) on an unforked module, dirs accumulate until the session exits
  * — bounded, session-scoped growth, not the old permanent leak.
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
