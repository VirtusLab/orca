package orca.shell.create

/** A throwaway git workspace for one authoring run (ADR 0021 §9): the authoring
  * flow runs here instead of in the user's repository, so authoring works from
  * any directory, never stashes or commits the user's tree, and leaves no
  * branches behind. A pre-committed settings file counts as "stack configured"
  * (`SettingsFile.hasStackLines` only sees LIVE lines — a comment would not do)
  * and keeps stack discovery from ever running. The one mechanical gate a flow
  * script admits is compiling it, so `lint` is `scala-cli compile <flow-file>`
  * — the review loop then gets compiler feedback each round — while
  * `format`/`test` stay off. On success the authored file is copied out to the
  * real tier and the sandbox deleted; after a failure it is kept for
  * inspection.
  */
private[shell] object AuthoringSandbox:

  private[create] def settingsContents(flowFileName: String): String =
    s"""# orca authoring sandbox — compiling the flow script is its only gate;
       |# there is no project stack to format or test.
       |format = off
       |lint = scala-cli compile ${shellQuote(flowFileName)}
       |test = off
       |""".stripMargin

  /** Single-quoted for the `bash -c` the lint gate runs under — the filename is
    * user-entered, so spaces (or a stray quote) must not split the command.
    */
  private def shellQuote(name: String): String =
    "'" + name.replace("'", "'\\''") + "'"

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
    * tree with a commit for its branch machinery to build on. `flowFileName` is
    * the authored file's basename at the sandbox root
    * (`AuthorAction.sandboxTarget`), baked into the lint gate's compile
    * command.
    */
  def create(flowFileName: String): os.Path =
    val dir = os.temp.dir(prefix = "orca-authoring-")
    git(dir, "init", "--quiet")
    git(dir, "config", "user.name", "orca")
    git(dir, "config", "user.email", "orca@authoring.invalid")
    os.write(dir / ".gitignore", GitignoreContents)
    os.makeDir.all(dir / ".orca")
    os.write(
      dir / ".orca" / "settings.properties",
      settingsContents(flowFileName)
    )
    git(dir, "add", "-A")
    git(dir, "commit", "--quiet", "-m", "orca authoring sandbox")
    dir

  def delete(dir: os.Path): Unit = os.remove.all(dir)

  private def git(dir: os.Path, args: String*): Unit =
    val _ = os
      .proc("git" +: args)
      .call(cwd = dir, stdout = os.Pipe, stderr = os.Pipe)
