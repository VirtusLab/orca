package orca.shell.sessions

import orca.agents.BackendTag
import orca.runner.manifest.ManifestSession
import orca.settings.AgentSpec

/** Per-harness interactive resume argv (ADR 0021 §8's resume table). This is
  * harness-session REATTACH — exec'ing the harness CLI's own resume flag
  * against a recorded wire id so its conversation continues — distinct from a
  * flow's own crash/resume (stage replay from the progress log, ADR 0018
  * §2.4/§2.5): that resumes a RUN, this resumes a CHAT.
  */
private[shell] object ResumeCommand:

  /** The wire id to resume `s` by, or why it is not resumable as far as a
    * static (no-live-call) check can tell: a wireId-less session — one that
    * never committed a turn. `Right` doesn't mean "definitely resumable" —
    * gemini's row still needs [[build]]'s live index lookup, and pi's its
    * session-dir check.
    */
  def staticGate(s: ManifestSession): Either[String, String] =
    s.wireId.toRight(
      s"${AgentSpec.harnessNameFor(s.backend)} session has no resumable id"
    )

  /** Left = not resumable: [[staticGate]]'s checks, plus whatever the caller's
    * live lookups report — gemini's `geminiIndex` (it resumes by index, not by
    * uuid, so the caller looks the wire id up in
    * [[orca.tools.gemini.GeminiSessionList]]) and pi's `piSessionDir` (its
    * transcripts live on disk;
    * [[orca.shell.actions.SessionAction.piSessionDir]] resolves the path or
    * says why it can't). Each lookup is a function invoked only by its own
    * harness's branch, with the already-validated wire id — a non-applicable
    * lookup is never called, so it can't be mistaken for a failed one.
    *
    * Binary names come from [[AgentSpec.harnessNameFor]] (the settings-file
    * spelling — `claude`, `codex`, …). Also rejects a blank wireId or one
    * starting with `-` — passed straight into an argv slot, such a value could
    * otherwise be parsed as a flag by the harness CLI.
    */
  def build(
      s: ManifestSession,
      geminiIndex: String => Option[Int],
      piSessionDir: String => Either[String, os.Path]
  ): Either[String, Seq[String]] =
    staticGate(s).flatMap: wireId =>
      if wireId.isBlank || wireId.startsWith("-") then
        Left(s"manifest wireId `$wireId` is not a valid session id")
      else
        val binary = AgentSpec.harnessNameFor(s.backend)
        s.backend match
          case BackendTag.ClaudeCode =>
            Right(Seq(binary, "--resume", wireId))
          case BackendTag.Codex    => Right(Seq(binary, "resume", wireId))
          case BackendTag.Opencode => Right(Seq(binary, "--session", wireId))
          case BackendTag.Gemini =>
            geminiIndex(wireId) match
              case Some(index) =>
                Right(Seq(binary, "--resume", index.toString))
              case None =>
                Left(
                  s"no matching session found via `$binary --list-sessions`"
                )
          case BackendTag.Pi =>
            // An absolute --session-dir, so the argv doesn't depend on the
            // child's cwd matching the manifest's workDir.
            piSessionDir(wireId).map: dir =>
              Seq(binary, "--session-dir", dir.toString, "--continue")
