package orca.shell.create

/** A throwaway git workspace for one authoring run (ADR 0021 §9): the authoring
  * flow runs here instead of in the user's repository, so authoring works from
  * any directory, never stashes or commits the user's tree, and leaves no
  * branches behind. A pre-committed settings file with every stack key
  * explicitly `off` counts as "stack configured" (`SettingsFile.hasStackLines`
  * only sees LIVE lines — a comment would not do) and keeps stack discovery
  * from ever running — a flow script has no project stack to discover. On
  * success the authored file is copied out to the real tier and the sandbox
  * deleted; after a failure it is kept for inspection.
  */
private[shell] object AuthoringSandbox:

  private[create] val SettingsContents: String =
    """# orca authoring sandbox — a flow script has no project stack; every
      |# gate is explicitly disabled.
      |format = off
      |lint = off
      |test = off
      |""".stripMargin

  /** Ignores the workspace metadata the coding agent's own `scala-cli compile`
    * (the prompt's verification step) drops beside the flow — kept out of
    * reviewer diffs and stage commits alike.
    */
  private[create] val GitignoreContents: String =
    """.bsp/
      |.scala-build/
      |.metals/
      |""".stripMargin

  /** Creates the sandbox: a fresh temp dir, `git init` with a local identity
    * (no dependence on the user's global git config), and the `.gitignore` +
    * settings file committed as the root commit — so the flow starts on a clean
    * tree with a commit for its branch machinery to build on.
    */
  def create(): os.Path =
    val dir = os.temp.dir(prefix = "orca-authoring-")
    git(dir, "init", "--quiet")
    git(dir, "config", "user.name", "orca")
    git(dir, "config", "user.email", "orca@authoring.invalid")
    os.write(dir / ".gitignore", GitignoreContents)
    os.makeDir.all(dir / ".orca")
    os.write(dir / ".orca" / "settings.properties", SettingsContents)
    git(dir, "add", "-A")
    git(dir, "commit", "--quiet", "-m", "orca authoring sandbox")
    dir

  def delete(dir: os.Path): Unit = os.remove.all(dir)

  private def git(dir: os.Path, args: String*): Unit =
    val _ = os
      .proc("git" +: args)
      .call(cwd = dir, stdout = os.Pipe, stderr = os.Pipe)
