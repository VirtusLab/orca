package orca.shell.create

import orca.settings.{SettingsFile, SettingsScope}

class AuthoringSandboxTest extends munit.FunSuite:

  private val flowName = "flow.sc"

  test(
    "create: an initialized git repo with a clean tree and a root commit"
  ):
    val sandbox = AuthoringSandbox.create(flowName)
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
    val sandbox = AuthoringSandbox.create(flowName)
    try
      val settings = os.read(sandbox / ".orca" / "settings.properties")
      assert(
        SettingsFile.hasStackLines(settings),
        s"live stack lines must satisfy the discovery trigger:\n$settings"
      )
    finally AuthoringSandbox.delete(sandbox)

  test("create: lint compiles the flow file; format and test stay off"):
    val sandbox = AuthoringSandbox.create(flowName)
    try
      val settings = os.read(sandbox / ".orca" / "settings.properties")
      val stack = SettingsFile
        .parse(settings, SettingsScope.Project)
        .fold(e => fail(e.message), _.stack)
      assertEquals(stack.lint, List("scala-cli compile 'flow.sc'"))
      assertEquals(stack.format, Nil)
      assertEquals(stack.test, Nil)
    finally AuthoringSandbox.delete(sandbox)

  test("settingsContents: a quote in the filename survives shell quoting"):
    val contents = AuthoringSandbox.settingsContents("it's.sc")
    val stack = SettingsFile
      .parse(contents, SettingsScope.Project)
      .fold(e => fail(e.message), _.stack)
    assertEquals(stack.lint, List("scala-cli compile 'it'\\''s.sc'"))

  test("delete removes the sandbox"):
    val sandbox = AuthoringSandbox.create(flowName)
    AuthoringSandbox.delete(sandbox)
    assert(!os.exists(sandbox))

  test(
    "create: scala-cli workspace droppings are ignored — invisible to status"
  ):
    val sandbox = AuthoringSandbox.create(flowName)
    try
      val tracked = os
        .proc("git", "ls-files")
        .call(cwd = sandbox)
        .out
        .text()
      assert(
        tracked.linesIterator.contains(".gitignore"),
        s"the sandbox .gitignore must be committed:\n$tracked"
      )
      os.write(sandbox / ".bsp" / "scala-cli.json", "{}", createFolders = true)
      os.write(
        sandbox / ".scala-build" / "ide-inputs.json",
        "{}",
        createFolders = true
      )
      val status = os
        .proc("git", "status", "--porcelain", "--untracked-files=all")
        .call(cwd = sandbox)
        .out
        .text()
      assertEquals(status.trim, "", status)
    finally AuthoringSandbox.delete(sandbox)
