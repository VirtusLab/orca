package orca.runner

/** Test helper: a fresh temp git repo with one seed commit, so `flow(...)` —
  * which now stashes, branches, and commits a progress log as part of its
  * lifecycle (ADR 0018 §2.5) — runs in isolation instead of mutating the
  * developer's checkout.
  */
object TempRepo:
  def create(): os.Path =
    val dir = os.temp.dir()
    val _ = os.proc("git", "init", "-b", "main").call(cwd = dir)
    val _ =
      os.proc("git", "config", "user.email", "test@example.com").call(cwd = dir)
    val _ = os.proc("git", "config", "user.name", "Test").call(cwd = dir)
    os.write(dir / "seed.txt", "seed")
    val _ = os.proc("git", "add", "-A").call(cwd = dir)
    val _ = os.proc("git", "commit", "-m", "seed").call(cwd = dir)
    dir
