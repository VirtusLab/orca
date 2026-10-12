package orca.runlog

import orca.{AttemptId, StagePath}
import orca.agents.BackendTag
import orca.gitref.BranchName
import orca.sessions.SessionRecord

import java.time.Instant

class SessionProjectionTest extends munit.FunSuite:

  private val at = Instant.parse("2026-10-08T10:00:00Z")
  private val attempt = AttemptId(Instant.ofEpochMilli(1000), pid = 42)
  private val stage = StagePath.FlowBody.child("plan", 0)

  private def minted(
      name: String,
      id: String,
      stage: StagePath = stage
  ): RunEvent.SessionMinted =
    RunEvent.SessionMinted(
      at,
      attempt,
      name,
      stage,
      id,
      s"seed-$id",
      BackendTag.Pi
    )

  private def wireId(id: String, wire: String): RunEvent.SessionWireId =
    RunEvent.SessionWireId(at, attempt, id, wire)

  private val succeeded = RunEvent.RunSucceeded(
    at,
    attempt,
    BranchName.parse("b").fold(e => fail(e), identity),
    None
  )

  private def record(
      name: String,
      id: String,
      wire: Option[String] = None,
      stage: StagePath = stage
  ): SessionRecord =
    SessionRecord(name, stage, id, s"seed-$id", wire, BackendTag.Pi)

  test("keeps records in mint order"):
    assertEquals(
      SessionProjection.records(List(minted("b", "1"), minted("a", "2"))),
      List(record("b", "1"), record("a", "2"))
    )

  test("a re-mint replaces the key's record in place"):
    assertEquals(
      SessionProjection.records(
        List(minted("a", "1"), minted("b", "2"), minted("a", "3"))
      ),
      List(record("a", "3"), record("b", "2"))
    )

  test("the same name in another stage is another record"):
    val code = StagePath.FlowBody.child("code", 0)
    assertEquals(
      SessionProjection.records(List(minted("a", "1"), minted("a", "2", code))),
      List(record("a", "1"), record("a", "2", stage = code))
    )

  test("a re-mint drops the replaced record's wire id"):
    assertEquals(
      SessionProjection.records(
        List(minted("a", "1"), wireId("1", "w"), minted("a", "3"))
      ),
      List(record("a", "3"))
    )

  test("a wire id is set on the record with that id"):
    assertEquals(
      SessionProjection.records(
        List(minted("a", "1"), minted("b", "2"), wireId("2", "w"))
      ),
      List(record("a", "1"), record("b", "2", Some("w")))
    )

  test("a wire id for an unknown id is ignored"):
    assertEquals(
      SessionProjection.records(List(minted("a", "1"), wireId("9", "w"))),
      List(record("a", "1"))
    )

  test("only events after the last success count"):
    assertEquals(
      SessionProjection.records(
        List(
          minted("a", "1"),
          succeeded,
          minted("b", "2"),
          succeeded,
          wireId("2", "w"),
          minted("c", "3")
        )
      ),
      List(record("c", "3"))
    )
