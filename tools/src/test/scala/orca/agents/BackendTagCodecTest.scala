package orca.agents

/** Machine-checked source of truth for [[BackendTag]]'s wire codec (6B.1).
  * `wireName` is the on-disk/wire representation persisted into
  * [[orca.progress.SessionRecord.backend]] — it must stay pinned to the CURRENT
  * (pre-codec) `toString` of every case, or every already-persisted session log
  * silently strands on the next resume. Mirrors `EnforcementTableTest`'s "the
  * rendered/frozen contract lives elsewhere; this test is what keeps it honest"
  * shape.
  */
class BackendTagCodecTest extends munit.FunSuite:

  test("wireName is pinned to each case's pre-codec toString value"):
    // Frozen literals, not `case.toString` — a test that compared against
    // `.toString` would silently stop pinning anything the moment a case is
    // renamed, exactly the failure mode this codec exists to prevent.
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
    // Every case is covered above — a new case added without updating this
    // table fails here, not silently.
    assertEquals(BackendTag.values.toSet, expected.keySet)

  test("fromWireName round-trips every case's wireName"):
    for tag <- BackendTag.values do
      assertEquals(BackendTag.fromWireName(tag.wireName), Some(tag))

  test("fromWireName rejects a string matching no known wireName"):
    assertEquals(BackendTag.fromWireName("Bogus"), None)
    assertEquals(BackendTag.fromWireName(""), None)
    // Case-sensitive: no silent normalization.
    assertEquals(BackendTag.fromWireName("codex"), None)
