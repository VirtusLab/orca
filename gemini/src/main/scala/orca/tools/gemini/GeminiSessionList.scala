package orca.tools.gemini

/** `gemini --list-sessions`, gemini's per-project session listing: read by the
  * backend's resume probe and by the shell, which resumes a gemini chat by its
  * position in this list rather than by id.
  */
private[orca] object GeminiSessionList:

  val argv: Seq[String] = Seq("gemini", "--list-sessions")

  /** Whether `listing` mentions the session `id` anywhere. Independent of the
    * entry-line format [[indexOf]] assumes, which is built from gemini's source
    * rather than captured output: the backend's resume probe only needs
    * presence, and a format mismatch there would silently re-seed every resumed
    * session.
    */
  def mentions(listing: String, id: String): Boolean =
    listing.linesIterator.exists(_.contains(id))

  // Matches one entry line: "  N. <title> (<time>) [<id>]".
  private val entryLine = raw"^\s*(\d+)\.\s.*\[(.+)\]\s*$$".r

  /** 1-based position of the session `id` in `listing` (the command's stdout),
    * or `None` if absent (including the empty-list message, "No previous
    * sessions found for this project."). Format pinned against gemini-cli
    * 0.50.0's own `listSessions` source (`packages/cli/src/utils/sessions.ts`,
    * read from the installed CLI's bundled `gemini-APOZRZEF.js`, not just its
    * docs — the docs' example shortens the id to 8 characters, but the code
    * interpolates the full session uuid): ` ${index + 1}. ${title}
    * (${relativeTime}) [${uuid}]` per line. The empty-list message itself was
    * captured verbatim from the installed CLI in a scratch dir
    * (`GeminiSessionListTest`'s fixture note has the exact invocation) — no
    * real populated list could be captured on this machine (no valid Gemini API
    * key to complete a turn and create one), so that shape is built from the
    * verified source instead.
    */
  def indexOf(listing: String, id: String): Option[Int] =
    listing.linesIterator.collectFirst:
      case entryLine(index, entryId) if entryId == id => index.toInt
