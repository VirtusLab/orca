package orca.shell.sessions

import orca.agents.BackendTag
import orca.runner.ManifestSession

class ResumeCommandTest extends munit.FunSuite:

  private def session(
      harness: String,
      wireId: Option[String],
      reason: Option[String] = None
  ): ManifestSession =
    ManifestSession(
      harness = harness,
      wireId = wireId,
      resumable = wireId.isDefined,
      reason = reason,
      agent = "agent",
      role = None,
      stage = None,
      sessionName = None,
      kind = "oneShot",
      firstSeenAt = "2026-07-18T10:00:00Z",
      lastActiveAt = "2026-07-18T10:00:00Z"
    )

  test("claude resumes via `claude --resume <uuid>`"):
    val uuid = "6f0f1234-5678-4abc-9def-000000000001"
    assertEquals(
      ResumeCommand
        .build(session("ClaudeCode", Some(uuid)), geminiIndex = None),
      Right(Seq("claude", "--resume", uuid))
    )

  test("codex resumes via `codex resume <thread-id>`"):
    val id = "7f9f1234-5678-4abc-9def-000000000002"
    assertEquals(
      ResumeCommand.build(session("Codex", Some(id)), geminiIndex = None),
      Right(Seq("codex", "resume", id))
    )

  test("opencode resumes via `opencode --session <ses_...>`"):
    val id = "ses_abc123"
    assertEquals(
      ResumeCommand.build(session("Opencode", Some(id)), geminiIndex = None),
      Right(Seq("opencode", "--session", id))
    )

  test("gemini resumes via `gemini --resume <index>` once the index is known"):
    val uuid = "aaaa1234-5678-4abc-9def-000000000003"
    assertEquals(
      ResumeCommand.build(session("Gemini", Some(uuid)), geminiIndex = Some(3)),
      Right(Seq("gemini", "--resume", "3"))
    )

  test(
    "gemini is not resumable when its wireId has no match in --list-sessions"
  ):
    val reason = "no matching session found via `gemini --list-sessions`"
    val uuid = "aaaa1234-5678-4abc-9def-000000000003"
    assertEquals(
      ResumeCommand.build(
        session("Gemini", Some(uuid), reason = Some(reason)),
        geminiIndex = None
      ),
      Left(reason)
    )

  test("pi is never resumable, reporting the manifest's stored reason"):
    val reason =
      "pi sessions are deleted when the run's temp dir is reclaimed"
    assertEquals(
      ResumeCommand
        .build(session("Pi", None, reason = Some(reason)), geminiIndex = None),
      Left(reason)
    )

  test(
    "a wireId-less session of any harness reports the manifest's stored reason (precedence over a generic message)"
  ):
    val reason = "crashed before the first turn committed"
    assertEquals(
      ResumeCommand.build(
        session("ClaudeCode", None, reason = Some(reason)),
        geminiIndex = None
      ),
      Left(reason)
    )

  test(
    "a wireId-less session with no stored reason still reports Left with some message"
  ):
    assert(
      ResumeCommand
        .build(session("ClaudeCode", None), geminiIndex = None)
        .isLeft
    )

  test("a wireId starting with `-` is rejected rather than passed as argv"):
    assert(
      ResumeCommand
        .build(session("ClaudeCode", Some("-rf")), geminiIndex = None)
        .isLeft
    )

  test("a blank wireId is rejected rather than passed as argv"):
    assert(
      ResumeCommand
        .build(session("ClaudeCode", Some("   ")), geminiIndex = None)
        .isLeft
    )

  test("an unrecognised harness string is not resumable"):
    assert(
      ResumeCommand
        .build(session("SomeFutureHarness", Some("id")), geminiIndex = None)
        .isLeft
    )

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
