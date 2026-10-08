package orca.runlog

import orca.{AttemptId, StagePath}
import orca.agents.{BackendTag, SessionKey}
import orca.events.{Cost, CostBasis, StageOutcome, Usage}
import orca.gitref.BranchName

import java.time.{Instant, LocalDate}

/** Pins each event's line: the public format of the run event log. */
class RunEventTest extends munit.FunSuite:

  private val at = Instant.parse("2026-10-08T10:00:00Z")
  private val attempt = AttemptId(Instant.ofEpochMilli(1000), pid = 42)
  private val common = """"at":"2026-10-08T10:00:00Z","attempt":"1000-42""""

  private val stage = StagePath.FlowBody.child("review", 0).child("fix", 1)
  private val stageJson =
    """[{"name":"review","occurrence":0},{"name":"fix","occurrence":1}]"""

  private def branch(raw: String): BranchName =
    BranchName.parse(raw).fold(e => fail(e), identity)

  private def assertLine(event: RunEvent, json: String): Unit =
    assertEquals(RunEvent.encodeLine(event), json)
    assertEquals(RunEvent.decodeLine(json), Some(event))

  test("AttemptStarted"):
    assertLine(
      RunEvent.AttemptStarted(
        at,
        attempt,
        schema = RunEvent.Schema,
        orcaVersion = "1.2.3",
        flow = Some("implement.sc"),
        workDir = "/w",
        pid = 42,
        trace = Some("/w/t.log")
      ),
      s"""{"type":"AttemptStarted",$common,"schema":1,"orcaVersion":"1.2.3","flow":"implement.sc","workDir":"/w","pid":42,"trace":"/w/t.log"}"""
    )

  test("BranchBound"):
    assertLine(
      RunEvent.BranchBound(at, attempt, branch("orca/feature")),
      s"""{"type":"BranchBound",$common,"branch":"orca/feature"}"""
    )

  test("StageStarted"):
    assertLine(
      RunEvent.StageStarted(at, attempt, stage),
      s"""{"type":"StageStarted",$common,"stage":$stageJson}"""
    )

  test("StageEnded"):
    assertLine(
      RunEvent.StageEnded(at, attempt, stage, StageOutcome.Replayed),
      s"""{"type":"StageEnded",$common,"stage":$stageJson,"outcome":"Replayed"}"""
    )

  test("SessionMinted"):
    assertLine(
      RunEvent.SessionMinted(
        at,
        attempt,
        name = "implementer",
        stage = StagePath.FlowBody,
        id = "id-1",
        seed = "seed",
        backend = BackendTag.Codex
      ),
      s"""{"type":"SessionMinted",$common,"name":"implementer","stage":[],"id":"id-1","seed":"seed","backend":"Codex"}"""
    )

  test("SessionWireId"):
    assertLine(
      RunEvent.SessionWireId(at, attempt, id = "id-1", wireId = "wire-1"),
      s"""{"type":"SessionWireId",$common,"id":"id-1","wireId":"wire-1"}"""
    )

  test("SessionCommitted"):
    assertLine(
      RunEvent.SessionCommitted(
        at,
        attempt,
        backend = BackendTag.ClaudeCode,
        wireId = Some("wire-1"),
        conversationKey = "wire-1",
        agent = "claude",
        role = Some("reviewer"),
        minted = Some(SessionKey("implementer", stage)),
        stage = Some(stage)
      ),
      s"""{"type":"SessionCommitted",$common,"backend":"ClaudeCode","wireId":"wire-1","conversationKey":"wire-1","agent":"claude","role":"reviewer","minted":{"name":"implementer","stage":$stageJson},"stage":$stageJson}"""
    )

  test("Turn"):
    assertLine(
      RunEvent.Turn(
        at,
        attempt,
        agent = "claude",
        role = None,
        model = Some("claude-sonnet-5"),
        stage = Some(stage),
        turn = 2,
        apiCalls = Some(3L),
        usage = TurnUsage(1, 2, 3, 4, 5),
        cost = Some(
          Cost(
            BigDecimal("0.5"),
            CostBasis.Estimated(LocalDate.of(2026, 9, 22))
          )
        ),
        conversationKey = "c"
      ),
      s"""{"type":"Turn",$common,"agent":"claude","model":"claude-sonnet-5","stage":$stageJson,"turn":2,"apiCalls":3,"usage":{"freshInputTokens":1,"cacheReadInputTokens":2,"cacheWriteInputTokens":3,"outputTokens":4,"reasoningOutputTokens":5},"cost":{"amount":0.5,"basis":{"type":"Estimated","ratesAsOf":"2026-09-22"}},"conversationKey":"c"}"""
    )

  test("RunSucceeded"):
    assertLine(
      RunEvent.RunSucceeded(at, attempt, branch("orca/feature"), Some("#12")),
      s"""{"type":"RunSucceeded",$common,"branch":"orca/feature","published":"#12"}"""
    )

  test("AttemptFinished"):
    assertLine(
      RunEvent.AttemptFinished(at, attempt, AttemptOutcome.Failed),
      s"""{"type":"AttemptFinished",$common,"outcome":"Failed"}"""
    )

  test("an optional field may be null"):
    assertEquals(
      RunEvent.decodeLine(
        s"""{"type":"RunSucceeded",$common,"branch":"b","published":null}"""
      ),
      Some(RunEvent.RunSucceeded(at, attempt, branch("b"), None))
    )

  test("a line whose branch is not a branch name does not decode"):
    assertEquals(
      RunEvent.decodeLine(
        s"""{"type":"BranchBound",$common,"branch":"a..b"}"""
      ),
      None
    )

  test("a line whose attempt is not an attempt id does not decode"):
    assert(
      RunEvent
        .decodeLine(
          """{"type":"BranchBound","at":"2026-10-08T10:00:00Z","attempt":"x","branch":"b"}"""
        )
        .isEmpty
    )

  // Without this, an axis added to Usage leaves `TurnUsage.of` compiling and
  // the log silently under-records spend.
  test("TurnUsage mirrors every token axis of Usage"):
    assertEquals(
      TurnUsage.of(Usage.empty).productElementNames.toSet,
      Usage.empty.productElementNames.toSet - "cost" - "apiCalls"
    )
