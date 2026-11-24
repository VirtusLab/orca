package orca.backend.cursor

import java.util.Locale

private[orca] object CursorWorkspaceTrust:
  def required(text: String): Boolean =
    text.toLowerCase(Locale.ROOT).contains("workspace trust required")

  def guidance(workDir: os.Path): String =
    val quoted = "'" + workDir.toString.replace("'", "'\"'\"'") + "'"
    s"Workspace Trust Required for $workDir. Run `cd $quoted && cursor-agent` " +
      "in a terminal, interactively trust this exact checkout, then rerun Orca. " +
      "Each fresh worktree may require its own trust decision."

  def explain(text: String, workDir: os.Path): String =
    if required(text) then guidance(workDir) else text
