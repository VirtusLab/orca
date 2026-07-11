package orca

import orca.util.RawJson
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
    assertEquals(entry.map(_.resultJson.value), Some("\"done\""))

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
    assert(
      ids.contains("outer#0/inner#0"),
      s"inner must be recorded under its path id; got $ids"
    )
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
        StageEntry("typed#0", "typed", RawJson("\"not-an-int\""))
      )
    val result = stage[Int]("typed"):
      val _ = ran.incrementAndGet()
      99
    assertEquals(result, 99)
    assertEquals(ran.get(), 1, "an undecodable entry must re-run the body")

  test(
    "resume: a skipped nested-parent's later same-named sibling runs its own body (same-type)"
  ):
    // Run 1: `outer` (with a nested `inner` recording "A") completes; a later
    // top-level `inner` stage crashes before it records. On resume `outer` is
    // skipped, so its nested `inner` never re-runs. Under a flat per-run counter
    // the top-level `inner` would recompute the nested inner's id and silently
    // replay "A"; under hierarchical ids the nested inner lives at
    // `outer#0/inner#0`, so the top-level `inner#0` has no record and RUNS.
    val listener = new RecordingListener
    val (ctx, dir) = TestFlowControl.create(new EventDispatcher(List(listener)))

    def runFlow(topInner: () => String)(using FlowControl): String =
      val _ = stage("outer"):
        val _ = stage("inner")("A")
        "outer-done"
      stage("inner")(topInner())

    val _ = intercept[RuntimeException]:
      runFlow(() => throw new RuntimeException("boom"))(using ctx)

    val (ctx2, _) = reopen(dir, listener)
    val result = runFlow(() => "B")(using ctx2)

    assertEquals(
      result,
      "B",
      "the resumed top-level `inner` must run its own body, not replay the " +
        "nested inner's stale 'A'"
    )

  test(
    "resume: a skipped nested-parent does not clobber the nested record via a later same-named sibling (different-type)"
  ):
    // Same setup as the same-type case, but the nested `inner` yields an Int and
    // the top-level `inner` a String. Under a flat counter the top-level inner
    // recomputes the nested inner's id, fails to decode the Int as a String,
    // re-runs, and its `appendEntry` upserts OVER the nested Int record — losing
    // it. Under hierarchical ids the two live at distinct paths and both survive.
    val listener = new RecordingListener
    val (ctx, dir) = TestFlowControl.create(new EventDispatcher(List(listener)))

    def runFlow(topInner: () => String)(using FlowControl): String =
      val _ = stage("outer"):
        val _ = stage[Int]("inner")(42)
        "outer-done"
      stage[String]("inner")(topInner())

    val _ = intercept[RuntimeException]:
      runFlow(() => throw new RuntimeException("boom"))(using ctx)

    val (ctx2, _) = reopen(dir, listener)
    val result = runFlow(() => "B")(using ctx2)

    assertEquals(result, "B")
    val entries = ctx2.progressStore.load().get.entries
    assert(
      entries.exists(_.resultJson.value == "42"),
      s"the nested inner's Int record must survive resume intact; got $entries"
    )
    assert(
      entries.exists(_.resultJson.value == "\"B\""),
      s"the top-level inner's String record must be recorded; got $entries"
    )
    assert(
      entries.exists(_.id == "outer#0/inner#0"),
      s"the nested inner must be recorded under its path id; got $entries"
    )

  test(
    "resume: a skipped stage still consumes its parent-frame occurrence slot (sibling stability)"
  ):
    // Two same-named top-level stages. On resume the first is skipped, but it
    // must still consume occurrence slot 0 in the (root) parent frame so the
    // second keeps id `dup#1` and replays its own value rather than collapsing
    // onto `dup#0`.
    val listener = new RecordingListener
    val (ctx, dir) = TestFlowControl.create(new EventDispatcher(List(listener)))

    def runFlow(using FlowControl): (String, String) =
      val a = stage("dup")("first")
      val b = stage("dup")("second")
      (a, b)

    assertEquals(runFlow(using ctx), ("first", "second"))
    val (ctx2, _) = reopen(dir, listener)
    assertEquals(
      runFlow(using ctx2),
      ("first", "second"),
      "both same-named siblings must replay their own recorded values on resume"
    )
    val ids = ctx2.progressStore.load().get.entries.map(_.id).toSet
    assertEquals(ids, Set("dup#0", "dup#1"), s"sibling ids must be stable")

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
