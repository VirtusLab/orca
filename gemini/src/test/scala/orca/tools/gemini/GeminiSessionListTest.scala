package orca.tools.gemini

class GeminiSessionListTest extends munit.FunSuite:

  // Populated shape built from gemini-cli 0.50.0's own `listSessions` source
  // (see GeminiSessionList.indexOf's scaladoc for provenance) — real ids are
  // full UUIDs, not the 8-char form shown in the CLI's own docs example.
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

  test("indexOf finds the 1-based index of a present session uuid"):
    assertEquals(
      GeminiSessionList.indexOf(
        populatedListOutput,
        "22222222-2222-4222-8222-222222222222"
      ),
      Some(2)
    )

  test("indexOf returns None for a uuid absent from a populated list"):
    assertEquals(
      GeminiSessionList.indexOf(
        populatedListOutput,
        "99999999-9999-4999-8999-999999999999"
      ),
      None
    )

  test("indexOf returns None for the empty-list output"):
    assertEquals(
      GeminiSessionList.indexOf(
        emptyListOutput,
        "22222222-2222-4222-8222-222222222222"
      ),
      None
    )
