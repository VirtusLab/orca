package orca.testkit

/** Test helper: a throwaway temp git repo. `empty` does `git init -b main` plus
  * user config; `seeded` adds one `seed.txt` commit so a flow's stash/branch/
  * commit lifecycle (ADR 0018 §2.5) runs in isolation from the dev checkout.
  *
  * Every repo's temp root is registered with [[TempDirs]] and swept at JVM
  * shutdown — see `TempDirs` scaladoc for why that (rather than plain
  * `os.temp.dir(deleteOnExit = true)`) is required to actually reclaim these
  * directories.
  */
object GitRepo:
  /** Fresh temp repo: `git init -b main` + test user config. No commits. */
  def empty(): os.Path =
    val dir = TempDirs.dir(prefix = "orca-gitrepo-")
    val _ = os.proc("git", "init", "-b", "main").call(cwd = dir)
    val _ =
      os.proc("git", "config", "user.email", "test@example.com").call(cwd = dir)
    val _ = os.proc("git", "config", "user.name", "Test").call(cwd = dir)
    dir

  /** `empty()` plus a single `seed.txt` commit (`seed`). */
  def seeded(): os.Path =
    val dir = empty()
    os.write(dir / "seed.txt", "seed")
    val _ = os.proc("git", "add", "-A").call(cwd = dir)
    val _ = os.proc("git", "commit", "-m", "seed").call(cwd = dir)
    dir
