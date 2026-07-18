package orca.agents

/** Machine-checked source of truth for [[BackendTag]]'s wire codec. `wireName`
  * is the on-disk representation persisted into
  * [[orca.progress.SessionRecord.backend]]; it must stay pinned to each case's
  * frozen value, or already-persisted session logs strand on the next resume.
  */
class BackendTagCodecTest extends munit.FunSuite:

  test("wireName is pinned to each case's pre-codec toString value"):
    // Frozen literals, not `case.toString`: comparing against `.toString` would
    // stop pinning the moment a case is renamed — the failure this test guards.
    val expected = Map(
      BackendTag.ClaudeCode -> "ClaudeCode",
      BackendTag.Codex -> "Codex",
      BackendTag.Opencode -> "Opencode",
      BackendTag.Pi -> "Pi",
      BackendTag.Gemini -> "Gemini"
    )
    assertEquals(
      BackendTag.values.map(t => t -> t.wireName).toMap,
      expected
    )
    // A new case added without updating the table above fails here.
    assertEquals(BackendTag.values.toSet, expected.keySet)

  test("fromWireName round-trips every case's wireName"):
    for tag <- BackendTag.values do
      assertEquals(BackendTag.fromWireName(tag.wireName), Some(tag))

  test("fromWireName rejects a string matching no known wireName"):
    assertEquals(BackendTag.fromWireName("Bogus"), None)
    assertEquals(BackendTag.fromWireName(""), None)
    // Case-sensitive: no silent normalization.
    assertEquals(BackendTag.fromWireName("codex"), None)
