package orca.shell.create

/** A throwaway git workspace for one authoring run (ADR 0021 §9): the authoring
  * flow runs here instead of in the user's repository, so authoring works from
  * any directory, never stashes or commits the user's tree, and leaves no
  * branches behind. A pre-committed settings file whose commented stack lines
  * count as "stack configured" (`SettingsFile.hasStackLines`) keeps stack
  * discovery from ever running — a flow script has no project stack to
  * discover. On success the authored file is copied out to the real tier and
  * the sandbox deleted; after a failure it is kept for inspection.
  */
private[shell] object AuthoringSandbox:

  private[create] val SettingsContents: String =
    """# orca authoring sandbox — a flow script has no project stack; every gate
      |# is disabled, and these commented lines keep stack discovery off.
      |# format =   (authoring sandbox)
      |# lint =   (authoring sandbox)
      |# test =   (authoring sandbox)
      |""".stripMargin

  /** Creates the sandbox: a fresh temp dir, `git init` with a local identity
    * (no dependence on the user's global git config), and the settings file
    * committed as the root commit — so the flow starts on a clean tree with a
    * commit for its branch machinery to build on.
    */
  def create(): os.Path =
    val dir = os.temp.dir(prefix = "orca-authoring-")
    git(dir, "init", "--quiet")
    git(dir, "config", "user.name", "orca")
    git(dir, "config", "user.email", "orca@authoring.invalid")
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
