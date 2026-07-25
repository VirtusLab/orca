package orca.subprocess

/** Whether a directory sits inside a git work tree (ADR 0021 §9 amendment):
  * `git rev-parse --is-inside-work-tree`, exit 0 ⇒ yes. Shared by the shell's
  * global-tier authoring guard — the authoring flow needs a real git repo to
  * bind its branch to, even though a global-tier target file itself lands
  * outside it.
  */
private[orca] object GitRepoProbe:

  def isInsideWorkTree(cwd: os.Path): Boolean =
    QuietProc
      .call(Seq("git", "rev-parse", "--is-inside-work-tree"), cwd = cwd)
      .exitCode == 0
