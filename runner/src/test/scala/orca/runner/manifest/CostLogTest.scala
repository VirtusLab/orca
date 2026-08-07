package orca.runner.manifest

import orca.OrcaDir
import orca.agents.Model
import orca.events.{Cost, OrcaEvent, PriceList, Pricing}
import orca.testkit.TempDirs
import orca.testkit.Usages.usage

import java.time.Instant

/** The `<id>-cost.jsonl` half of the run record (ADR 0021 §8 amendment,
  * 2026-08-05). The session half stays in [[RunManifestWriterTest]].
  */
class CostLogTest extends munit.FunSuite:

  private def fixedClock(at: Instant): () => Instant = () => at

  private def newWriter(
      workDir: os.Path,
      pricing: PriceList = Pricing.default
  ): RunManifestWriterState =
    new RunManifestWriterState(
      workDir,
      "0.0.test",
      Some("review-pr.sc"),
      pricing,
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

  test("a turn-only run writes a cost log and no session manifest"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir)
    writer.onEvent(OrcaEvent.TokensUsed("claude", None, usage(10, 1, None)))
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
        .SessionCommitted("claude", "client-1", Some("wire-1"), "claude", None)
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
    writer.onEvent(OrcaEvent.TokensUsed("claude", None, usage(10, 1, None)))
    writer.onEvent(OrcaEvent.TokensUsed("claude", None, usage(20, 2, None)))
    assertEquals(
      costRecords(workDir).collect { case r: CostRecord.Run => r },
      List(
        CostRecord.Run("0.0.test", Some("review-pr.sc"), workDir.toString)
      )
    )

  test("finish appends the outcome as the log's last record"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir)
    writer.onEvent(OrcaEvent.TokensUsed("claude", None, usage(10, 1, None)))
    writer.finish(RunOutcome.Failed)
    assertEquals(
      costRecords(workDir).last,
      CostRecord.Finish("2026-07-18T10:00:00Z", RunManifest.OutcomeFailed)
    )

  /** No trailer is how a reader tells a killed run from a finished one, since
    * `outcome` lives in the session manifest a turn-only run never writes.
    */
  test("a run that never finishes leaves the log without a trailer"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir)
    writer.onEvent(OrcaEvent.TokensUsed("claude", None, usage(10, 1, None)))
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
        .SessionCommitted("claude", "client-1", Some("wire-1"), "claude", None)
    )
    writer.onEvent(
      OrcaEvent.TokensUsed(
        "claude",
        None,
        usage(107_000, 500, None, apiCalls = Some(3L)),
        None,
        session = Some("wire-1")
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
        attempt = 2
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
    * figures are non-zero and unequal so both survive the projection, and a
    * write is billed at the write rate rather than folded into base input.
    */
  test("a turn carries every usage axis and its resolved cost"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir)
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
        role = None
      )
    )
    val turn = turns(workDir).head
    assertEquals(
      turn.usage,
      ManifestUsage(
        inputTokens = 120_000,
        outputTokens = 900,
        cacheReadInputTokens = 107_000,
        cacheWriteInputTokens = 8_000,
        reasoningOutputTokens = 0
      )
    )
    // 13k fresh at $3/M + 107k cache-read at $0.30/M + 8k cache-write at $6/M +
    // 900 out at $15/M.
    assertEquals(turn.cost, Some(Cost(BigDecimal("0.1086"), estimated = true)))

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
        role = None
      )
    )
    writer.onEvent(
      OrcaEvent.TokensUsed(
        agent = "reviewer",
        model = Some(Model("claude-haiku-4-5")),
        usage = usage(5_000, 100, Some(BigDecimal("0.0123"))),
        role = Some("reviewer")
      )
    )
    val recorded = turns(workDir)
    assertEquals(recorded.map(_.usage.inputTokens).sum, 125_000L)
    // 13k fresh at $3/M + 107k cache-read at $0.30/M + 900 out at $15/M =
    // $0.0846, plus the second turn's reported $0.0123.
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
    log.append(CostRecord.Finish("a", "succeeded"))
    os.write.append(log.path, "{\"type\":\"Turn\",\"at\":\"tor")
    log.append(CostRecord.Finish("b", "failed"))
    assertEquals(log.read(), List(CostRecord.Finish("a", "succeeded")))

  /** A tear can cut a multi-byte character in half — stage and agent names are
    * free-form and jsoniter emits them unescaped. A reporting decoder throws on
    * that before yielding any line at all, so what this pins is that the lines
    * BEFORE the tear still come back.
    */
  test("read survives a tear through a multi-byte character"):
    val workDir = TempDirs.dir()
    val log = CostLog(workDir / "runs" / "1-1-cost.jsonl")
    log.append(CostRecord.Finish("a", "succeeded"))
    // The first two bytes of "€" (E2 82 AC), then nothing.
    os.write.append(log.path, Array(0xe2.toByte, 0x82.toByte))
    assertEquals(log.read(), List(CostRecord.Finish("a", "succeeded")))

  test("read skips a record kind it does not know"):
    val workDir = TempDirs.dir()
    val log = CostLog(workDir / "runs" / "1-1-cost.jsonl")
    log.append(CostRecord.Finish("a", "succeeded"))
    os.write.append(log.path, "{\"type\":\"FromALaterBuild\",\"at\":\"b\"}\n")
    assertEquals(log.read(), List(CostRecord.Finish("a", "succeeded")))

  /** The manifest half of `finish` is an idempotent rewrite; the cost half is
    * an append, so a second call must not leave a second trailer.
    */
  test("a second finish does not append a second trailer"):
    val workDir = TempDirs.dir()
    val writer = newWriter(workDir)
    writer.onEvent(OrcaEvent.TokensUsed("claude", None, usage(10, 1, None)))
    writer.finish(RunOutcome.Succeeded)
    writer.finish(RunOutcome.Failed)
    assertEquals(
      costRecords(workDir).count(_.isInstanceOf[CostRecord.Finish]),
      1
    )
