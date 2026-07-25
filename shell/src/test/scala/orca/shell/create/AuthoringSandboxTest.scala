package orca.shell.create

import orca.settings.SettingsFile

class AuthoringSandboxTest extends munit.FunSuite:

  test(
    "create: an initialized git repo with a clean tree and a root commit"
  ):
    val sandbox = AuthoringSandbox.create()
    try
      assert(os.isDir(sandbox / ".git"))
      val status = os
        .proc("git", "status", "--porcelain")
        .call(cwd = sandbox)
        .out
        .text()
      assertEquals(status.trim, "", "the sandbox tree must start clean")
      val commits = os
        .proc("git", "rev-list", "--count", "HEAD")
        .call(cwd = sandbox)
        .out
        .text()
      assertEquals(commits.trim, "1")
    finally AuthoringSandbox.delete(sandbox)

  test("create: the settings file suppresses stack discovery"):
    val sandbox = AuthoringSandbox.create()
    try
      val settings = os.read(sandbox / ".orca" / "settings.properties")
      assert(
        SettingsFile.hasStackLines(settings),
        s"commented stack lines must satisfy the discovery trigger:\n$settings"
      )
    finally AuthoringSandbox.delete(sandbox)

  test("delete removes the sandbox"):
    val sandbox = AuthoringSandbox.create()
    AuthoringSandbox.delete(sandbox)
    assert(!os.exists(sandbox))
