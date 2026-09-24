package orca.shell.sessions

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import orca.{AttemptId, OrcaDir, StagePath}
import orca.agents.{BackendTag, SessionKey}
import orca.runner.manifest.{AttemptManifest, AttemptStatus, ManifestSession}

import java.time.Instant

/** Manifests and sessions as the shell's tests build them, encoded through the
  * production codec when written to disk, so a fixture can never drift from the
  * shape the reader accepts.
  */
private[shell] object ManifestFixtures:

  def manifest(
      workDir: String = "/work",
      startedAt: String = "2026-07-18T10:00:00Z",
      pid: Long = 1,
      status: AttemptStatus = AttemptStatus.Succeeded,
      sessions: List[ManifestSession],
      branch: Option[String] = None
  ): AttemptManifest =
    AttemptManifest(
      orcaVersion = "0.0.test",
      flow = Some("a-flow.sc"),
      workDir = workDir,
      branch = branch,
      pid = pid,
      startedAt = Instant.parse(startedAt),
      finishedAt = None,
      status = status,
      sessions = sessions
    )

  /** A session minted under `sessionName` in `sessionStage`. */
  def durable(
      agent: String = "main",
      sessionName: String = "main",
      sessionStage: StagePath = StagePath.FlowBody,
      stage: Option[String] = None,
      lastActiveAt: String = "2026-07-18T10:00:00Z",
      backend: BackendTag = BackendTag.ClaudeCode,
      wireId: Option[String] = Some("uuid")
  ): ManifestSession =
    ManifestSession(
      backend = backend,
      wireId = wireId,
      agent = agent,
      role = None,
      stage = stage,
      minted = Some(
        SessionKey(
          name = sessionName,
          stage = sessionStage
        )
      ),
      lastActiveAt = Instant.parse(lastActiveAt)
    )

  def ephemeral(
      agent: String = "main",
      role: Option[String] = None,
      stage: Option[String] = None,
      lastActiveAt: String = "2026-07-18T10:00:00Z",
      backend: BackendTag = BackendTag.ClaudeCode,
      wireId: Option[String] = Some("uuid")
  ): ManifestSession =
    ManifestSession(
      backend = backend,
      wireId = wireId,
      agent = agent,
      role = role,
      stage = stage,
      minted = None,
      lastActiveAt = Instant.parse(lastActiveAt)
    )

  /** `manifest` as [[ManifestReader]] records it: under the attempt id its
    * `startedAt` and `pid` spell.
    */
  def recorded(
      manifest: AttemptManifest,
      observedStatus: ObservedStatus
  ): RecordedAttempt =
    RecordedAttempt(
      AttemptId(manifest.startedAt, manifest.pid),
      manifest,
      observedStatus
    )

  /** [[recorded]] with the writing process still alive. */
  def recorded(manifest: AttemptManifest): RecordedAttempt =
    recorded(manifest, ObservedStatus.of(manifest, _ => true))

  /** `session`, recorded in `manifest`, as a [[SessionIndex]] holds it, with
    * the writing process still alive.
    */
  def selection(
      manifest: AttemptManifest,
      session: ManifestSession
  ): SessionSelection =
    selection(manifest, session, ObservedStatus.of(manifest, _ => true))

  /** `session`, recorded in `manifest`, as a [[SessionIndex]] holds it. */
  def selection(
      manifest: AttemptManifest,
      session: ManifestSession,
      observedStatus: ObservedStatus
  ): SessionSelection =
    SessionSelection(
      SessionRef(
        AttemptId(manifest.startedAt, manifest.pid),
        manifest.sessions.indexOf(session) + 1
      ),
      manifest,
      session,
      observedStatus
    )

  /** Writes `manifest` where `ManifestReader` lists `dir`'s attempts, under the
    * attempt id its `startedAt` and `pid` spell.
    */
  def writeManifest(dir: os.Path, manifest: AttemptManifest): Unit =
    os.write(
      OrcaDir.manifestPath(dir, AttemptId(manifest.startedAt, manifest.pid)),
      writeToString(manifest)(using AttemptManifest.codec),
      createFolders = true
    )
