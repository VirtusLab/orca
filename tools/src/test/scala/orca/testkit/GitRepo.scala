package orca.testkit

/** Test helper: a throwaway temp git repo. `empty` does `git init -b main` plus
  * user config; `seeded` adds one `seed.txt` commit. Every repo's temp root is
  * registered with [[TempDirs]] for cleanup at JVM shutdown.
  */
object GitRepo:
  /** Fresh temp repo: `git init -b main`, test user config, and the ambient
    * global config neutralised. No commits.
    */
  def empty(): os.Path =
    val dir = TempDirs.dir(prefix = "orca-gitrepo-")
    val _ = os.proc("git", "init", "-b", "main").call(cwd = dir)
    val _ =
      os.proc("git", "config", "user.email", "test@example.com").call(cwd = dir)
    val _ = os.proc("git", "config", "user.name", "Test").call(cwd = dir)
    // Neutralise the two settings a developer's global config can reach into
    // the fixture with: git reads a missing path as empty. A global ignore rule
    // matching a fixture name would otherwise hide it from `git status`, and
    // the test would fail with no hint why. Under `.git/`, out of the tree.
    val gitDir = dir / ".git"
    val _ = os
      .proc("git", "config", "core.excludesFile", gitDir / "no-excludes")
      .call(cwd = dir)
    val _ = os
      .proc("git", "config", "core.hooksPath", gitDir / "no-hooks")
      .call(cwd = dir)
    dir

  /** `empty()` plus a single `seed.txt` commit (`seed`). */
  def seeded(): os.Path =
    val dir = empty()
    os.write(dir / "seed.txt", "seed")
    val _ = os.proc("git", "add", "-A").call(cwd = dir)
    val _ = os.proc("git", "commit", "-m", "seed").call(cwd = dir)
    dir
