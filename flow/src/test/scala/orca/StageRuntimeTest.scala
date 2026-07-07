package orca

import orca.events.{EventDispatcher, OrcaEvent, OrcaListener}
import orca.progress.StageEntry

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

/** The stage runtime's resume + commit guarantees (ADR 0018 §2.1/§2.4). */
class StageRuntimeTest extends munit.FunSuite:

  private class RecordingListener extends OrcaListener:
    private val seen: AtomicReference[List[OrcaEvent]] = AtomicReference(Nil)
    def onEvent(event: OrcaEvent): Unit =
      val _ = seen.updateAndGet(event :: _)
    def events: List[OrcaEvent] = seen.get().reverse

  test("a completed stage commits both code changes and the progress log"):
    val (ctx, dir) = TestFlowControl.create(new EventDispatcher(Nil))
    given FlowControl = ctx
    val before = commitCount(dir)
    val _ = stage("write file"):
      os.write(dir / "out.txt", "hello")
      "done"
    assertEquals(commitCount(dir), before + 1)
    // The progress file is tracked even though nothing gitignores it here.
    assert(
      tracked(dir).contains(ctx.progressStore.path.relativeTo(dir).toString),
      "progress log must be committed"
    )
    assert(tracked(dir).contains("out.txt"), "code change must be committed")
    // And the result is recorded for resume.
    val entry =
      ctx.progressStore.load().get.entries.find(_.id == "write file#0")
    assertEquals(entry.map(_.resultJson), Some("\"done\""))

  test("re-running replays the stored result without running the body again"):
    val listener = new RecordingListener
    val (ctx, dir) = TestFlowControl.create(new EventDispatcher(List(listener)))
    val runs = new AtomicInteger(0)

    def runOnce()(using FlowControl): String =
      stage("compute"):
        val _ = runs.incrementAndGet()
        os.write(dir / "marker.txt", "x")
        "value-42"

    val first = runOnce()(using ctx)
    // A second control over the SAME repo + store: a fresh process re-run.
    val (ctx2, _) = reopen(dir, listener)
    val second = runOnce()(using ctx2)

    assertEquals(first, "value-42")
    assertEquals(second, "value-42")
    assertEquals(runs.get(), 1, "body must run exactly once across both runs")
    assert(
      listener.events.contains(
        OrcaEvent.Step("Resuming 'compute' from recorded result")
      ),
      "second run must report a resume"
    )

  test("a crash in a later stage leaves earlier stages committed and recorded"):
    val (ctx, dir) = TestFlowControl.create(new EventDispatcher(Nil))
    given FlowControl = ctx
    val _ = stage("stage one"):
      os.write(dir / "one.txt", "1")
      "one-result"
    val countAfterOne = commitCount(dir)

    val _ = intercept[RuntimeException]:
      stage[String]("stage two"):
        os.write(dir / "two.txt", "2")
        throw new RuntimeException("boom")

    // Stage one's commit + record survive the crash in stage two.
    assertEquals(commitCount(dir), countAfterOne)
    val ids = ctx.progressStore.load().get.entries.map(_.id)
    assert(ids.contains("stage one#0"), "stage one must remain recorded")
    assert(
      !ids.contains("stage two#0"),
      "the crashed stage must not be recorded"
    )

  test("a nested stage commits and records both the outer and inner stages"):
    val (ctx, dir) = TestFlowControl.create(new EventDispatcher(Nil))
    given FlowControl = ctx
    val before = commitCount(dir)
    val _ = stage("outer"):
      os.write(dir / "outer.txt", "o")
      val _ = stage("inner"):
        os.write(dir / "inner.txt", "i")
        "inner-result"
      "outer-result"
    // Two stages → two commits (one per stage, inner committing before outer).
    assertEquals(commitCount(dir), before + 2)
    val ids = ctx.progressStore.load().get.entries.map(_.id)
    assert(ids.contains("inner#0"), s"inner must be recorded; got $ids")
    assert(ids.contains("outer#0"), s"outer must be recorded; got $ids")

  test(
    "a plain exception unwinding through nested stages is reported exactly once"
  ):
    val listener = new RecordingListener
    val (ctx, _) = TestFlowControl.create(new EventDispatcher(List(listener)))
    given FlowControl = ctx
    val _ = intercept[RuntimeException]:
      stage[String]("outer"):
        val _ = stage[String]("inner"):
          throw new RuntimeException("boom")
        "outer-result"
    val errors = listener.events.collect { case e: OrcaEvent.Error => e }
    assertEquals(
      errors.size,
      1,
      s"a plain exception must surface one Error as it unwinds, got: $errors"
    )

  test(
    "a single stage throwing MalformedAgentOutputException reports one Error"
  ):
    val listener = new RecordingListener
    val (ctx, _) = TestFlowControl.create(new EventDispatcher(List(listener)))
    given FlowControl = ctx
    val _ = intercept[orca.agents.MalformedAgentOutputException]:
      stage[String]("parse"):
        throw new orca.agents.MalformedAgentOutputException(
          "raw output",
          "not JSON",
          new RuntimeException("boom")
        )
    val errors = listener.events.collect { case e: OrcaEvent.Error => e }
    assertEquals(errors.size, 1, s"exactly one Error expected, got: $errors")
    assert(
      errors.head.message.contains("didn't parse as structured JSON"),
      s"MAO must use the malformed-output render, got: ${errors.head.message}"
    )

  test(
    "a MalformedAgentOutputException unwinding through nested stages reports one Error"
  ):
    // Pins the guard-first ordering that makes MAO exactly-once: the inner
    // stage renders it, marks it reported, and the outer stage skips it.
    val listener = new RecordingListener
    val (ctx, _) = TestFlowControl.create(new EventDispatcher(List(listener)))
    given FlowControl = ctx
    val _ = intercept[orca.agents.MalformedAgentOutputException]:
      stage[String]("outer"):
        val _ = stage[String]("inner"):
          throw new orca.agents.MalformedAgentOutputException(
            "raw output",
            "not JSON",
            new RuntimeException("boom")
          )
        "outer-result"
    val errors = listener.events.collect { case e: OrcaEvent.Error => e }
    assertEquals(errors.size, 1, s"exactly one Error expected, got: $errors")
    assert(
      errors.head.message.contains("didn't parse as structured JSON"),
      s"MAO must use the malformed-output render, got: ${errors.head.message}"
    )

  test("an undecodable stored entry re-runs the stage"):
    val (ctx, _) = TestFlowControl.create(new EventDispatcher(Nil))
    given FlowControl = ctx
    val ran = new AtomicInteger(0)
    // Seed an entry under id "typed#0" whose JSON cannot decode to Int.
    locally:
      given WorkspaceWrite = WorkspaceWrite.unsafe
      ctx.progressStore.appendEntry(
        StageEntry("typed#0", "typed", "\"not-an-int\"")
      )
    val result = stage[Int]("typed"):
      val _ = ran.incrementAndGet()
      99
    assertEquals(result, 99)
    assertEquals(ran.get(), 1, "an undecodable entry must re-run the body")

  // --- helpers ---

  private def reopen(
      dir: os.Path,
      listener: OrcaListener
  ): (TestFlowControl, os.Path) =
    val git = new orca.tools.OsGitTool(dir)
    val store = orca.progress.ProgressStore.default(dir, "p")
    (
      new TestFlowControl(
        new EventDispatcher(List(listener)),
        git,
        store,
        "p"
      ),
      dir
    )

  private def commitCount(dir: os.Path): Int =
    os.proc("git", "rev-list", "--count", "HEAD")
      .call(cwd = dir)
      .out
      .text()
      .trim
      .toInt

  private def tracked(dir: os.Path): Set[String] =
    os.proc("git", "ls-files")
      .call(cwd = dir)
      .out
      .text()
      .linesIterator
      .toSet
