package orca.backend.mcp

import io.circe.parser.decode

class RepoMcpServerTest extends munit.FunSuite:

  test("git_show decodes a call that omits the optional arguments"):
    // The tool's description and the system-prompt hint both say `paths` and
    // `stat` are optional, so the agent omits them. Scala default values do not
    // reach the derived circe codec, which rejects a missing non-Option field —
    // measured live as `DecodingFailure at .paths: Missing required field`,
    // leaving the agent with one working git tool instead of two.
    // Asserted on the outcome rather than the constructor shape, so restoring
    // defaulted fields fails this test rather than failing to compile it.
    val decoded = decode[GitShowInput]("""{"rev":"HEAD"}""")
    assert(decoded.isRight, decoded)
