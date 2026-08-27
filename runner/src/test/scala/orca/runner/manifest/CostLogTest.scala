package orca.runner.manifest

import orca.OrcaDir
import orca.agents.Model
import orca.events.{Cost, OrcaEvent}
import orca.testkit.TempDirs
import orca.testkit.Usages.usage

import java.time.Instant

/** The `<id>-cost.jsonl` half of the run record (ADR 0021 §8 amendment,
  * 2026-08-05). The session half stays in [[RunManifestWriterTest]].
  */
class CostLogTest extends munit.FunSuite:

  private def fixedClock(at: Instant): () => Instant = () => at

  private def newWriter(workDir: os.Path): RunManifestWriterState =
    new RunManifestWriterState(
      workDir,
      "0.0.test",
      Some("review-pr.sc"),
      fixedClock(Instant.parse("2026-07-18T10:00:00Z"))
    )

  private def costRecords(workDir: os.Path): List[CostRecord] =
    val files =
      os.list(OrcaDir.cacheRunsPath(workDir))
        .filter(_.last.endsWith("-cost.jsonl"))
        .toList
    assertEquals(files.size, 1, s"expected exactly one cost log, got: $files")
    CostLog(files.head).read()

  private def turns(workDir: os.Path): List[CostRecord.Turn] =
    costRecords(workDir).collect { case t: CostRecord.Turn => t }

  // Two trailers the read-side tests append and expect back; only their
  // distinctness matters there.
  private val firstFinish =
    CostRecord.Finish(Instant.parse("2026-07-18T10:00:00Z"), "succeeded")
  private val secondFinish =
    CostRecord.Finish(Instant.parse("2026-07-18T11:00:00Z"), "failed")

  test("a turn-only run writes a cost log and no session manifest"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir)
    writer.onEvent(
      OrcaEvent.TokensUsed("claude", None, usage(10, 1, None), cost = None)
    )
    writer.finish(RunOutcome.Succeeded)
    assertEquals(
      os.list(OrcaDir.cacheRunsPath(workDir)).filter(_.ext == "json").toList,
      Nil
    )
    assertEquals(turns(workDir).size, 1)

  test("a session-only run writes a manifest and no cost log"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir)
    writer.onEvent(
      OrcaEvent
        .SessionCommitted(
          harness = "claude",
          clientId = "client-1",
          wireId = Some("wire-1"),
          sessionName = None,
          agent = "claude",
          role = None
        )
    )
    writer.finish(RunOutcome.Succeeded)
    assertEquals(
      os.list(OrcaDir.cacheRunsPath(workDir))
        .filter(_.last.endsWith("-cost.jsonl"))
        .toList,
      Nil
    )

  /** The header repeats the run's identity so a cost log left by a run that
    * never committed a session is still readable on its own.
    */
  test("the cost log opens with one run header carrying the run's identity"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir)
    writer.onEvent(
      OrcaEvent.TokensUsed("claude", None, usage(10, 1, None), cost = None)
    )
    writer.onEvent(
      OrcaEvent.TokensUsed("claude", None, usage(20, 2, None), cost = None)
    )
    assertEquals(
      costRecords(workDir).collect { case r: CostRecord.Run => r },
      List(
        CostRecord.Run("0.0.test", Some("review-pr.sc"), workDir.toString)
      )
    )

  test("finish appends the outcome as the log's last record"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir)
    writer.onEvent(
      OrcaEvent.TokensUsed("claude", None, usage(10, 1, None), cost = None)
    )
    writer.finish(RunOutcome.Failed)
    assertEquals(
      costRecords(workDir).last,
      CostRecord.Finish(
        Instant.parse("2026-07-18T10:00:00Z"),
        ManifestOutcome.Failed.wireName
      )
    )

  /** No trailer is how a reader tells a killed run from a finished one, since
    * `outcome` lives in the session manifest a turn-only run never writes.
    */
  test("a run that never finishes leaves the log without a trailer"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir)
    writer.onEvent(
      OrcaEvent.TokensUsed("claude", None, usage(10, 1, None), cost = None)
    )
    assert(
      !costRecords(workDir).exists(_.isInstanceOf[CostRecord.Finish]),
      costRecords(workDir).toString
    )

  test("a turn records its identity, stage, attempt, session and API calls"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir)
    writer.onEvent(OrcaEvent.StageStarted("code"))
    writer.onEvent(
      OrcaEvent
        .SessionCommitted(
          harness = "claude",
          clientId = "client-1",
          wireId = Some("wire-1"),
          sessionName = None,
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
        attempt = 2,
        cost = None
      )
    )
    assertEquals(
      turns(workDir).map(t =>
        (t.agent, t.role, t.stage, t.attempt, t.session, t.apiCalls)
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
      ManifestUsage(
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

  /** The run total is a read-time fold, so this pins that the lines carry
    * enough to compute one — including `estimated` surviving the addition, so a
    * mixed total can't be read as a billed figure.
    */
  test("per-turn costs fold back into the run total"):
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

  /** A write that throws part-way leaves an unterminated line, which the next
    * append runs onto — so the tear costs that record and the one after it, and
    * nothing before.
    */
  test("read drops a torn line and keeps the whole ones before it"):
    val workDir = TempDirs.dir()
    val log = CostLog(workDir / "runs" / "1-1-cost.jsonl")
    log.append(firstFinish)
    os.write.append(log.path, "{\"type\":\"Turn\",\"at\":\"tor")
    log.append(secondFinish)
    assertEquals(log.read(), List(firstFinish))

  /** A tear can cut a multi-byte character in half — stage and agent names are
    * free-form and jsoniter emits them unescaped. A reporting decoder throws on
    * that before yielding any line at all, so what this pins is that the lines
    * BEFORE the tear still come back.
    */
  test("read survives a tear through a multi-byte character"):
    val workDir = TempDirs.dir()
    val log = CostLog(workDir / "runs" / "1-1-cost.jsonl")
    log.append(firstFinish)
    // The first two bytes of "€" (E2 82 AC), then nothing.
    os.write.append(log.path, Array(0xe2.toByte, 0x82.toByte))
    assertEquals(log.read(), List(firstFinish))

  test("read skips a record kind it does not know"):
    val workDir = TempDirs.dir()
    val log = CostLog(workDir / "runs" / "1-1-cost.jsonl")
    log.append(firstFinish)
    os.write.append(log.path, "{\"type\":\"FromALaterBuild\",\"at\":\"b\"}\n")
    assertEquals(log.read(), List(firstFinish))

  /** The manifest half of `finish` is an idempotent rewrite; the cost half is
    * an append, so a second call must not leave a second trailer.
    */
  test("a second finish does not append a second trailer"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir)
    writer.onEvent(
      OrcaEvent.TokensUsed("claude", None, usage(10, 1, None), cost = None)
    )
    writer.finish(RunOutcome.Succeeded)
    writer.finish(RunOutcome.Failed)
    assertEquals(
      costRecords(workDir).count(_.isInstanceOf[CostRecord.Finish]),
      1
    )
