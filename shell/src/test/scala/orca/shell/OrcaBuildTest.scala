package orca.shell

class OrcaBuildTest extends munit.FunSuite:

  test("of: a plain version is a release"):
    assertEquals(OrcaBuild.of("0.0.18"), OrcaBuild.Release("0.0.18"))

  test("of: a dynver '+'-suffixed version is a snapshot"):
    assertEquals(
      OrcaBuild.of("0.0.18+5-abc"),
      OrcaBuild.Snapshot("0.0.18+5-abc")
    )
