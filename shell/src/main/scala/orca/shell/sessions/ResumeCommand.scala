package orca.shell.sessions

import orca.agents.BackendTag
import orca.runner.ManifestSession
import orca.settings.AgentSpec

/** Per-harness interactive resume argv (ADR 0021 §8's resume table). */
private[shell] object ResumeCommand:

  /** Left = not resumable, checkable without a live harness call: an
    * unrecognised `harness` string, or a wireId-less session (pi always, since
    * its sessions never survive the run — the manifest's stored `reason` is
    * used when set, else a generic fallback). `Right` carries the recognised
    * [[BackendTag]] but doesn't mean "definitely resumable" — gemini's row
    * still needs [[build]]'s live index lookup. Used both by [[build]] itself
    * and by the shell's session-list preview (which has no `geminiIndex` yet).
    */
  private def wireIdAndTag(
      s: ManifestSession
  ): Either[String, (String, BackendTag)] =
    (s.wireId, BackendTag.fromWireName(s.harness)) match
      case (_, None) =>
        Left(s"unknown harness in manifest: `${s.harness}`")
      case (None, Some(_)) =>
        Left(s.reason.getOrElse(s"${s.harness} session has no resumable id"))
      case (Some(wireId), Some(tag)) => Right((wireId, tag))

  /** The static (no-live-call) half of [[build]]'s resumability check, exposed
    * for the shell's session-list preview.
    */
  def staticGate(s: ManifestSession): Either[String, BackendTag] =
    wireIdAndTag(s).map(_._2)

  /** Left = not resumable: [[staticGate]]'s checks, plus gemini when
    * `geminiIndex` is `None` (gemini resumes interactively by index, not by
    * uuid — the caller resolves the index by matching the session's wireId
    * against `gemini --list-sessions` output and passes it in). Binary names
    * come from [[AgentSpec.harnessNameFor]] (the settings-file spelling —
    * `claude`, `codex`, …), not the manifest's [[BackendTag.wireName]]. Also
    * rejects a blank wireId or one starting with `-` — passed straight into an
    * argv slot, such a value could otherwise be parsed as a flag by the harness
    * CLI.
    */
  def build(
      s: ManifestSession,
      geminiIndex: Option[Int]
  ): Either[String, Seq[String]] =
    wireIdAndTag(s).flatMap: (wireId, tag) =>
      if wireId.isBlank || wireId.startsWith("-") then
        Left(s"manifest wireId `$wireId` is not a valid session id")
      else
        val binary = AgentSpec.harnessNameFor.getOrElse(tag, s.harness)
        tag match
          case BackendTag.ClaudeCode =>
            Right(Seq(binary, "--resume", wireId))
          case BackendTag.Codex    => Right(Seq(binary, "resume", wireId))
          case BackendTag.Opencode => Right(Seq(binary, "--session", wireId))
          case BackendTag.Gemini =>
            geminiIndex match
              case Some(index) =>
                Right(Seq(binary, "--resume", index.toString))
              case None =>
                Left(
                  s.reason.getOrElse(
                    s"no matching session found via `$binary --list-sessions`"
                  )
                )
          case BackendTag.Pi =>
            Left(s.reason.getOrElse("pi sessions are not resumable"))

  // Matches one `--list-sessions` entry line: "  N. <title> (<time>) [<id>]".
  private val entryLine = raw"^\s*(\d+)\.\s.*\[(.+)\]\s*$$".r

  /** 1-based index of the session whose id is `uuid` in `gemini
    * --list-sessions` stdout, or `None` if absent (including the empty-list
    * message, "No previous sessions found for this project."). Format pinned
    * against gemini-cli 0.50.0's own `listSessions` source
    * (`packages/cli/src/utils/sessions.ts`, read from the installed CLI's
    * bundled `gemini-APOZRZEF.js`, not just its docs — the docs' example
    * shortens the id to 8 characters, but the code interpolates the full
    * session uuid): ` ${index + 1}. ${title} (${relativeTime}) [${uuid}]` per
    * line. The empty-list message itself was captured verbatim from the
    * installed CLI in a scratch dir (`ResumeCommandTest`'s fixture note has the
    * exact invocation) — no real populated list could be captured on this
    * machine (no valid Gemini API key to complete a turn and create one), so
    * that shape is built from the verified source instead.
    */
  def geminiIndexOf(listOutput: String, uuid: String): Option[Int] =
    listOutput.linesIterator.collectFirst:
      case entryLine(index, id) if id == uuid => index.toInt
