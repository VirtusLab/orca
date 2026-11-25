package orca.shell.sessions

import orca.agents.BackendTag
import orca.shell.sessions.EventLogFixtures.ephemeral

class ResumeCommandTest extends munit.FunSuite:

  private def session(
      backend: BackendTag,
      wireId: Option[String]
  ): RecordedSession =
    ephemeral(backend = backend, wireId = wireId)

  /** [[ResumeCommand.build]] with lookup stubs that fail the test if invoked —
    * each test overrides only the lookup its harness actually reads.
    */
  private def build(
      s: RecordedSession,
      geminiIndex: String => Option[Int] = _ => fail("gemini lookup invoked"),
      piSessionDir: String => Either[String, os.Path] = _ =>
        fail("pi lookup invoked"),
      cursorHistory: String => Either[CursorHistoryFailure, Unit] = _ =>
        fail("Cursor lookup invoked")
  ): Either[String, Seq[String]] =
    ResumeCommand
      .build(s, geminiIndex, piSessionDir, cursorHistory)
      .left
      .map(_.getMessage)

  test("claude resumes via `claude --resume <uuid>`"):
    val uuid = "6f0f1234-5678-4abc-9def-000000000001"
    assertEquals(
      build(session(BackendTag.ClaudeCode, Some(uuid))),
      Right(Seq("claude", "--resume", uuid))
    )

  test("codex resumes via `codex resume <thread-id>`"):
    val id = "7f9f1234-5678-4abc-9def-000000000002"
    assertEquals(
      build(session(BackendTag.Codex, Some(id))),
      Right(Seq("codex", "resume", id))
    )

  test("opencode resumes via `opencode --session <ses_...>`"):
    val id = "ses_abc123"
    assertEquals(
      build(session(BackendTag.Opencode, Some(id))),
      Right(Seq("opencode", "--session", id))
    )

  test("gemini resumes via `gemini --resume <index>` once the index is known"):
    val uuid = "aaaa1234-5678-4abc-9def-000000000003"
    assertEquals(
      build(session(BackendTag.Gemini, Some(uuid)), geminiIndex = _ => Some(3)),
      Right(Seq("gemini", "--resume", "3"))
    )

  test(
    "gemini is not resumable when its wireId has no match in --list-sessions"
  ):
    val reason = "no matching session found via `gemini --list-sessions`"
    val uuid = "aaaa1234-5678-4abc-9def-000000000003"
    assertEquals(
      build(
        session(BackendTag.Gemini, Some(uuid)),
        geminiIndex = _ => None
      ),
      Left(reason)
    )

  test("pi resumes via `pi --session-dir <dir> --continue`"):
    val dir = os.root / "work" / ".orca" / "cache" / "pi-sessions" / "a-session"
    assertEquals(
      build(
        session(BackendTag.Pi, Some("a-session")),
        piSessionDir = _ => Right(dir)
      ),
      Right(Seq("pi", "--session-dir", dir.toString, "--continue"))
    )

  test("pi is not resumable, with the caller's reason, when its dir is gone"):
    val reason = "no pi transcript at /gone — pruned, cleaned, or never written"
    assertEquals(
      build(
        session(BackendTag.Pi, Some("a-session")),
        piSessionDir = _ => Left(reason)
      ),
      Left(reason)
    )

  test(
    "a wireId-less session is not resumable"
  ):
    assert(build(session(BackendTag.ClaudeCode, None)).isLeft)

  test("a wireId starting with `-` is rejected rather than passed as argv"):
    assert(build(session(BackendTag.ClaudeCode, Some("-rf"))).isLeft)

  test("a blank wireId is rejected rather than passed as argv"):
    assert(build(session(BackendTag.ClaudeCode, Some("   "))).isLeft)

  test("staticGate: a session with a wireId passes"):
    assertEquals(
      ResumeCommand.staticGate(session(BackendTag.ClaudeCode, Some("uuid"))),
      Right("uuid")
    )

  test("Cursor reopens verified history interactively without print mode"):
    val uuid = "6f0f1234-5678-4abc-9def-000000000001"
    assertEquals(
      build(
        session(BackendTag.Cursor, Some(uuid)),
        cursorHistory = id =>
          assertEquals(id, uuid)
          Right(())
      ),
      Right(Seq("cursor-agent", s"--resume=$uuid"))
    )

  test("Cursor refuses missing history with the checkout-specific reason"):
    val reason = "no Cursor history in /checkout; rerun Orca there"
    assertEquals(
      build(
        session(
          BackendTag.Cursor,
          Some("6f0f1234-5678-4abc-9def-000000000001")
        ),
        cursorHistory = _ => Left(new CursorHistoryFailure(reason))
      ),
      Left(reason)
    )

  test("Cursor history failure stays typed until the shell boundary"):
    val failure = new CursorHistoryFailure("missing Cursor history; rerun Orca")
    val result = ResumeCommand.build(
      session(BackendTag.Cursor, Some("6f0f1234-5678-4abc-9def-000000000001")),
      geminiIndex = _ => fail("gemini lookup invoked"),
      piSessionDir = _ => fail("pi lookup invoked"),
      cursorHistory = _ => Left(failure)
    )
    assertEquals(result, Left(failure))

  test("Cursor rejects a malformed UUID before looking up history"):
    for id <- List("", "-x", "../../elsewhere", "safe-but-not-uuid", "abc\n") do
      assert(build(session(BackendTag.Cursor, Some(id))).isLeft)
