package orca.testkit

/** Test helper: a throwaway temp git repo. `empty` does `git init -b main` plus
  * user config; `seeded` adds one `seed.txt` commit. Every repo's temp root is
  * registered with [[TempDirs]] for cleanup at JVM shutdown.
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
