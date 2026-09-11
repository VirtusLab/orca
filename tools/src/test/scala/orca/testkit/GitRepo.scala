package orca.testkit

/** Test helper: a throwaway temp git repo. `empty` does `git init -b main` plus
  * user config; `seeded` adds one `seed.txt` commit. Every repo's temp root is
  * registered with [[TempDirs]] for cleanup at JVM shutdown.
  */
object GitRepo:
  /** Fresh temp repo: `git init -b main` plus test user config. No commits.
    *
    * The developer's global and system git config is out of the picture for the
    * whole test JVM (`GIT_CONFIG_GLOBAL`/`GIT_CONFIG_SYSTEM` in `build.sbt`),
    * which is why the user config below has to be set here.
    */
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
    seed(dir)
    dir

  /** [[seeded]] with the git directory kept outside the checkout
    * (`--separate-git-dir`); the checkout is returned. git names that git
    * directory as the repository's main worktree, so a linked worktree of this
    * repo has no main checkout to be found from.
    */
  def seededSeparateGitDir(): os.Path =
    val root = TempDirs.dir(prefix = "orca-gitrepo-")
    val checkout = root / "checkout"
    os.makeDir.all(checkout)
    val _ = os
      .proc(
        "git",
        "init",
        "-b",
        "main",
        s"--separate-git-dir=${root / "gitdir"}"
      )
      .call(cwd = checkout)
    val _ = os
      .proc("git", "config", "user.email", "test@example.com")
      .call(cwd = checkout)
    val _ = os.proc("git", "config", "user.name", "Test").call(cwd = checkout)
    seed(checkout)
    checkout

  private def seed(dir: os.Path): Unit =
    os.write(dir / "seed.txt", "seed")
    val _ = os.proc("git", "add", "-A").call(cwd = dir)
    val _ = os.proc("git", "commit", "-m", "seed").call(cwd = dir)
