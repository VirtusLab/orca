package orca.shell.sessions

import orca.runner.manifest.ManifestSession

/** How recorded sessions read to a person — shared by the interactive picker
  * ([[SessionPicker]]), `orca continue --list` and the pre-resume notice, so
  * they cannot drift.
  */
private[shell] object SessionNaming:

  /** How a row says which tree its session is in, given the index being
    * rendered and the row's `(workDir, branch)`: nothing when the sessions
    * share one `workDir` or the row has a branch, otherwise a ` @<dir>` suffix.
    */
  def dirTag(
      index: SessionIndex
  ): (String, Option[String]) => String =
    if index.listing.map(_.manifest.workDir).distinct.sizeIs <= 1 then
      (_, _) => ""
    else
      (workDir, branch) =>
        if branch.isDefined then "" else s" @${lastSegment(workDir)}"

  /** A recorded `workDir`'s final segment. String-sliced, not `os.Path`-parsed:
    * the value is manifest content, and a hand-edited one need not be an
    * absolute path.
    */
  private def lastSegment(workDir: String): String =
    workDir.split('/').filter(_.nonEmpty).lastOption.getOrElse(workDir)

  /** How a session reads to a person: the name it was minted under, or the
    * agent name for an ephemeral session.
    *
    * The name alone: the key's other half, the minting stage, is a path id
    * rather than prose. A row's `(stage: ...)` segment is a different field —
    * where the session was last active — so `SessionPicker.mintedInTag` is what
    * appends the minting stage where two rows need it to tell them apart.
    */
  def displayName(session: ManifestSession): String =
    session.minted.fold(session.agent)(_.name)

  /** ` (crashed)` for a crashed attempt's session; nothing otherwise. */
  def statusSuffix(status: ObservedStatus): String = status match
    case ObservedStatus.Crashed => " (crashed)"
    case ObservedStatus.Running | ObservedStatus.Succeeded |
        ObservedStatus.Failed =>
      ""
