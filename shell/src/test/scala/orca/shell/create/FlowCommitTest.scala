package orca.shell.create

import orca.testkit.{GitRepo, TempDirs}

class FlowCommitTest extends munit.FunSuite:

  private def commitCount(repo: os.Path): Int =
    os.proc("git", "rev-list", "--count", "HEAD")
      .call(cwd = repo)
      .out
      .text()
      .trim
      .toInt

  test("commits exactly the given path in a repo with a HEAD"):
    val repo = GitRepo.seeded()
    val path = repo / "new.sc"
    os.write(path, "// a flow\n")

    val committed = FlowCommit.commitScoped(path, repo, "orca: add flow new.sc")

    assert(committed)
    assertEquals(commitCount(repo), 2)
    val lastMessage =
      os.proc("git", "log", "-1", "--pretty=%s")
        .call(cwd = repo)
        .out
        .text()
        .trim
    assertEquals(lastMessage, "orca: add flow new.sc")

  test("declines without touching the tree when cwd isn't a git work tree"):
    val dir = TempDirs.dir()
    val path = dir / "new.sc"
    os.write(path, "// a flow\n")

    val committed = FlowCommit.commitScoped(path, dir, "orca: add flow new.sc")

    assert(!committed)
    assert(!os.isDir(dir / ".git"))

  test("declines on an unborn HEAD (fresh git init, no commits yet)"):
    val repo = GitRepo.empty()
    val path = repo / "new.sc"
    os.write(path, "// a flow\n")

    val committed = FlowCommit.commitScoped(path, repo, "orca: add flow new.sc")

    assert(!committed)
    val status =
      os.proc("git", "status", "--porcelain").call(cwd = repo).out.text()
    assert(status.contains("new.sc"), "the file must remain uncommitted")
