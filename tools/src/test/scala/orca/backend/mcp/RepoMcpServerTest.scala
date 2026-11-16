package orca.backend.mcp

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import orca.json.JsonData

import scala.util.Try

class RepoMcpServerTest extends munit.FunSuite:

  test("git_show decodes a call that omits the optional arguments"):
    // The tool's description and the system-prompt hint both say `paths` and
    // `stat` are optional, so the agent omits them; failing to decode such a
    // call leaves the agent with one working git tool instead of two.
    // Asserted on the outcome rather than the constructor shape, so restoring
    // defaulted fields fails this test rather than failing to compile it.
    val decoded = Try(
      readFromString[GitShowInput]("""{"rev":"HEAD"}""")(using
        summon[JsonData[GitShowInput]].codec
      )
    )
    assert(decoded.isSuccess, decoded)
