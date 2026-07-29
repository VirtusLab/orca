package orca.shell.sessions

import orca.agents.BackendTag
import orca.runner.manifest.ManifestSession

class ResumeCommandTest extends munit.FunSuite:

  private def session(
      harness: String,
      wireId: Option[String],
      reason: Option[String] = None
  ): ManifestSession =
    ManifestSession(
      harness = harness,
      wireId = wireId,
      reason = reason,
      agent = "agent",
      role = None,
      stage = None,
      sessionName = None,
      kind = "oneShot",
      firstSeenAt = "2026-07-18T10:00:00Z",
      lastActiveAt = "2026-07-18T10:00:00Z"
    )

  /** [[ResumeCommand.build]] with lookup stubs that fail the test if invoked —
    * each test overrides only the lookup its harness actually reads.
    */
  private def build(
      s: ManifestSession,
      geminiIndex: String => Option[Int] = _ => fail("gemini lookup invoked"),
      piSessionDir: String => Either[String, os.Path] = _ =>
        fail("pi lookup invoked")
  ): Either[String, Seq[String]] =
    ResumeCommand.build(s, geminiIndex, piSessionDir)

  test("claude resumes via `claude --resume <uuid>`"):
    val uuid = "6f0f1234-5678-4abc-9def-000000000001"
    assertEquals(
      build(session("ClaudeCode", Some(uuid))),
      Right(Seq("claude", "--resume", uuid))
    )

  test("codex resumes via `codex resume <thread-id>`"):
    val id = "7f9f1234-5678-4abc-9def-000000000002"
    assertEquals(
      build(session("Codex", Some(id))),
      Right(Seq("codex", "resume", id))
    )

  test("opencode resumes via `opencode --session <ses_...>`"):
    val id = "ses_abc123"
    assertEquals(
      build(session("Opencode", Some(id))),
      Right(Seq("opencode", "--session", id))
    )

  test("gemini resumes via `gemini --resume <index>` once the index is known"):
    val uuid = "aaaa1234-5678-4abc-9def-000000000003"
    assertEquals(
      build(session("Gemini", Some(uuid)), geminiIndex = _ => Some(3)),
      Right(Seq("gemini", "--resume", "3"))
    )

  test(
    "gemini is not resumable when its wireId has no match in --list-sessions"
  ):
    val reason = "no matching session found via `gemini --list-sessions`"
    val uuid = "aaaa1234-5678-4abc-9def-000000000003"
    assertEquals(
      build(
        session("Gemini", Some(uuid), reason = Some(reason)),
        geminiIndex = _ => None
      ),
      Left(reason)
    )

  test("pi resumes via `pi --session-dir <dir> --continue`"):
    val dir = os.root / "work" / ".orca" / "cache" / "pi-sessions" / "a-session"
    assertEquals(
      build(session("Pi", Some("a-session")), piSessionDir = _ => Right(dir)),
      Right(Seq("pi", "--session-dir", dir.toString, "--continue"))
    )

  test("pi is not resumable, with the caller's reason, when its dir is gone"):
    val reason = "no pi transcript at /gone — pruned, cleaned, or never written"
    assertEquals(
      build(session("Pi", Some("a-session")), piSessionDir = _ => Left(reason)),
      Left(reason)
    )

  test(
    "a wireId-less session of any harness reports the manifest's stored reason (precedence over a generic message)"
  ):
    val reason = "crashed before the first turn committed"
    assertEquals(
      build(session("ClaudeCode", None, reason = Some(reason))),
      Left(reason)
    )

  test(
    "a wireId-less session with no stored reason still reports Left with some message"
  ):
    assert(build(session("ClaudeCode", None)).isLeft)

  test("a wireId starting with `-` is rejected rather than passed as argv"):
    assert(build(session("ClaudeCode", Some("-rf"))).isLeft)

  test("a blank wireId is rejected rather than passed as argv"):
    assert(build(session("ClaudeCode", Some("   "))).isLeft)

  test("an unrecognised harness string is not resumable"):
    assert(build(session("SomeFutureHarness", Some("id"))).isLeft)

  test("staticGate: a recognised harness with a wireId is Right with its tag"):
    assertEquals(
      ResumeCommand.staticGate(session("ClaudeCode", Some("uuid"))),
      Right(BackendTag.ClaudeCode)
    )

  test(
    "staticGate: gemini with a wireId is Right, deferring the index check to build"
  ):
    assertEquals(
      ResumeCommand.staticGate(session("Gemini", Some("uuid"))),
      Right(BackendTag.Gemini)
    )

  // Populated shape built from gemini-cli 0.50.0's own `listSessions` source
  // (see ResumeCommand.geminiIndexOf's scaladoc for provenance) — real ids
  // are full UUIDs, not the 8-char form shown in the CLI's own docs example.
  private val populatedListOutput =
    "\nAvailable sessions for this project (3):\n" +
      "  1. Fix bug in auth (2 days ago) [11111111-1111-4111-8111-111111111111]\n" +
      "  2. Refactor database schema (5 hours ago) [22222222-2222-4222-8222-222222222222]\n" +
      "  3. Update documentation (Just now) [33333333-3333-4333-8333-333333333333]\n"

  // Captured verbatim: `GEMINI_API_KEY=dummy gemini --list-sessions` run from
  // an empty scratch dir with gemini-cli 0.50.0 installed on this machine —
  // the real auth check gates the command before the session lookup runs, so
  // a placeholder key unblocks it without a live session (none could be
  // created here — no valid Gemini API key to complete a turn).
  private val emptyListOutput = "No previous sessions found for this project."

  test("geminiIndexOf finds the 1-based index of a present session uuid"):
    assertEquals(
      ResumeCommand.geminiIndexOf(
        populatedListOutput,
        "22222222-2222-4222-8222-222222222222"
      ),
      Some(2)
    )

  test("geminiIndexOf returns None for a uuid absent from a populated list"):
    assertEquals(
      ResumeCommand.geminiIndexOf(
        populatedListOutput,
        "99999999-9999-4999-8999-999999999999"
      ),
      None
    )

  test("geminiIndexOf returns None for the empty-list output"):
    assertEquals(
      ResumeCommand.geminiIndexOf(
        emptyListOutput,
        "22222222-2222-4222-8222-222222222222"
      ),
      None
    )
