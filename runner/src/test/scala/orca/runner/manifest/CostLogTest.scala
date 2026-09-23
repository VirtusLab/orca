package orca.runner.manifest

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import orca.{AttemptId, OrcaDir}
import orca.agents.{BackendTag, Model}
import orca.events.{Cost, OrcaEvent, Usage}
import orca.testkit.TempDirs
import orca.testkit.Usages.usage

import java.time.Instant

/** The `<AttemptId>.cost.jsonl` half of the attempt record (ADR 0021 §8
  * amendment, 2026-08-05). The session half stays in
  * [[AttemptManifestWriterTest]].
  */
class CostLogTest extends munit.FunSuite:

  private def fixedClock(at: Instant): () => Instant = () => at

  private def newWriter(workDir: os.Path): AttemptManifestWriterState =
    new AttemptManifestWriterState(
      workDir,
      "0.0.test",
      Some("review-pr.sc"),
      AttemptId(Instant.parse("2026-07-18T10:00:00Z"), pid = 1),
      fixedClock(Instant.parse("2026-07-18T10:00:00Z"))
    )

  private def costLogFiles(workDir: os.Path): List[os.Path] =
    os.list(OrcaDir.ensureAttempts(workDir))
      .filter(_.last.endsWith(OrcaDir.CostLogSuffix))
      .toList

  private def turns(workDir: os.Path): List[CostRecord] =
    val files = costLogFiles(workDir)
    assertEquals(files.size, 1, s"expected exactly one cost log, got: $files")
    os.read
      .lines(files.head)
      .map(readFromString[CostRecord](_)(using CostRecord.codec))
      .toList

  test("a session-only attempt writes no cost log"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir)
    writer.onEvent(
      OrcaEvent
        .SessionCommitted(
          harness = BackendTag.ClaudeCode,
          clientId = "client-1",
          wireId = Some("wire-1"),
          sessionKey = None,
          agent = "claude",
          role = None
        )
    )
    writer.finish(AttemptOutcome.Succeeded)
    assertEquals(costLogFiles(workDir), Nil)

  test("each line is one turn: identity, stage, turn, session and API calls"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir)
    writer.onEvent(OrcaEvent.StageStarted("code"))
    writer.onEvent(
      OrcaEvent
        .SessionCommitted(
          harness = BackendTag.ClaudeCode,
          clientId = "client-1",
          wireId = Some("wire-1"),
          sessionKey = None,
          agent = "claude",
          role = None
        )
    )
    writer.onEvent(
      OrcaEvent.TokensUsed(
        "claude",
        None,
        usage(107_000, 500, None, apiCalls = Some(3L)),
        None,
        session = Some("wire-1"),
        cost = None
      )
    )
    // Closing the stage between the two turns pins that the stage is stamped
    // when the turn is appended, not at some later write.
    writer.onEvent(OrcaEvent.StageCompleted("code"))
    writer.onEvent(
      OrcaEvent.TokensUsed(
        "reviewer",
        None,
        usage(0, 0, None),
        Some("reviewer"),
        turn = 2,
        cost = None
      )
    )
    assertEquals(
      turns(workDir).map(t =>
        (t.agent, t.role, t.stage, t.turn, t.session, t.apiCalls)
      ),
      List(
        ("claude", None, Some("code"), 1, Some("wire-1"), Some(3L)),
        ("reviewer", Some("reviewer"), None, 2, None, None)
      )
    )

  /** Every axis is carried per turn, because the aggregates are folds over
    * these lines and nothing else persists them. The cache-read and cache-write
    * figures are non-zero and unequal so both survive the projection. Cost is
    * whatever the dispatcher already resolved — the writer prices nothing.
    */
  test("a turn carries every usage axis and the cost the event arrived with"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir)
    val resolved = Cost(BigDecimal("0.1086"), estimated = true)
    writer.onEvent(
      OrcaEvent.TokensUsed(
        agent = "claude",
        model = Some(Model("claude-sonnet-5")),
        usage = usage(
          input = 120_000,
          output = 900,
          cost = None,
          cacheRead = 107_000,
          cacheWrite = 8_000
        ),
        role = None,
        cost = Some(resolved)
      )
    )
    val turn = turns(workDir).head
    assertEquals(
      turn.usage,
      CostLogUsage(
        freshInputTokens = 5_000,
        cacheReadInputTokens = 107_000,
        cacheWriteInputTokens = 8_000,
        outputTokens = 900,
        reasoningOutputTokens = 0
      )
    )
    assertEquals(turn.cost, Some(resolved))

  /** Without the model on the line, the by-model split the run printed cannot
    * be reproduced from the file — which the record's own contract promises.
    */
  test("a turn records the model, or None when the backend reported none"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir)
    writer.onEvent(
      OrcaEvent.TokensUsed(
        agent = "claude",
        model = Some(Model("claude-sonnet-5")),
        usage = usage(10, 1, None),
        role = None,
        cost = None
      )
    )
    writer.onEvent(
      OrcaEvent.TokensUsed("claude", None, usage(10, 1, None), cost = None)
    )
    assertEquals(
      turns(workDir).map(_.model),
      List(Some("claude-sonnet-5"), None)
    )

  /** The attempt total is a read-time fold, so this pins that the lines carry
    * enough to compute one — including `estimated` surviving the addition, so a
    * mixed total can't be read as a billed figure.
    */
  test("per-turn costs fold back into the attempt total"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir)
    writer.onEvent(
      OrcaEvent.TokensUsed(
        agent = "claude",
        model = Some(Model("claude-sonnet-5")),
        usage = usage(120_000, 900, None, cacheRead = 107_000),
        role = None,
        cost = Some(Cost(BigDecimal("0.0846"), estimated = true))
      )
    )
    writer.onEvent(
      OrcaEvent.TokensUsed(
        agent = "reviewer",
        model = Some(Model("claude-haiku-4-5")),
        usage = usage(5_000, 100, Some(BigDecimal("0.0123"))),
        role = Some("reviewer"),
        cost = Some(Cost(BigDecimal("0.0123"), estimated = false))
      )
    )
    val recorded = turns(workDir)
    assertEquals(
      recorded
        .map(t =>
          t.usage.freshInputTokens + t.usage.cacheReadInputTokens +
            t.usage.cacheWriteInputTokens
        )
        .sum,
      125_000L
    )
    assertEquals(
      recorded.flatMap(_.cost).reduce(_ + _),
      Cost(BigDecimal("0.0969"), estimated = true)
    )

  // Guards the invariant CostLogUsage's scaladoc states: it mirrors every one
  // of Usage's token axes. Without this, an axis ADDED to Usage leaves
  // `CostLogUsage.of` compiling untouched and the cost log silently
  // under-records spend. Every aggregate is a fold over these lines, so an
  // unrecorded axis is unrecoverable rather than recomputable.
  test("CostLogUsage mirrors every token axis of Usage"):
    assertEquals(
      CostLogUsage.of(Usage.empty).productElementNames.toSet,
      // The two of Usage's fields that take another route: `cost` is not
      // carried at all (see CostLogUsage's scaladoc), and `apiCalls` sits
      // beside the usage on each turn line rather than inside it.
      Usage.empty.productElementNames.toSet - "cost" - "apiCalls"
    )
