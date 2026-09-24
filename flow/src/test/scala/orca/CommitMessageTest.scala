package orca

import orca.events.EventDispatcher
import orca.agents.{Agent, BackendTag}
import orca.sessions.SessionRecord
import orca.testkit.{ScriptedBackend, TestAgent, TextReplyingAgent}

import java.util.concurrent.ConcurrentLinkedQueue

/** Tests for the agent-generated commit-message path in `recordAndCommit`: wire
  * a real temp repo and a stubbed LLM, then assert the message in `git log`
  * after a stage runs.
  */
class CommitMessageTest extends munit.FunSuite:

  // --------------------------------------------------------------------------
  // Stubs
  // --------------------------------------------------------------------------

  /** LLM stub whose every turn fails. */
  private val throwingAgent: Agent[BackendTag.ClaudeCode.type] =
    TestAgent(
      ScriptedBackend.replying(BackendTag.ClaudeCode)(_ =>
        throw new RuntimeException("LLM unavailable")
      ),
      "throwing"
    )

  // --------------------------------------------------------------------------
  // Test helper
  // --------------------------------------------------------------------------

  private def withRun(
      agentStub: Agent[BackendTag.ClaudeCode.type]
  )(body: TestRun => Unit): Unit =
    body(TestRun.create(new EventDispatcher(Nil), lead = Some(agentStub)))

  private def lastCommitMessage(dir: os.Path): String =
    os.proc("git", "log", "-1", "--pretty=%s").call(cwd = dir).out.text().trim

  /** The next prompt the stub was given, failing the test rather than returning
    * `null` when the commit path never reached the model.
    */
  private def nextPrompt(prompts: ConcurrentLinkedQueue[String]): String =
    Option(prompts.poll()).getOrElse(fail("no prompt reached the agent"))

  // --------------------------------------------------------------------------
  // Tests
  // --------------------------------------------------------------------------

  test(
    "stage with no commitMessage and non-empty diff uses agent.cheap message"
  ):
    withRun(TextReplyingAgent("Add feature file")): run =>
      import run.given
      val _ = stage("write file"):
        // Modify the tracked seed file (not a new untracked file) so
        // `git diff HEAD` captures the change.
        os.write.over(run.dir / "seed.txt", "modified by stage")
        "done"
      assertEquals(lastCommitMessage(run.dir), "Add feature file")

  test("stage with no commitMessage but empty diff falls back to stage:<name>"):
    // An empty working-tree diff (no code changes, only the progress file
    // force-added) triggers the `s"stage: $name"` fallback.
    withRun(TextReplyingAgent("should not appear")): run =>
      import run.given
      val _ = stage("no-op"):
        "done"
      assertEquals(lastCommitMessage(run.dir), "stage: no-op")

  test(
    "stage with no commitMessage and throwing agent falls back to stage:<name>"
  ):
    withRun(throwingAgent): run =>
      import run.given
      val _ = stage("write file"):
        os.write.over(run.dir / "seed.txt", "modified by stage")
        "done"
      assertEquals(lastCommitMessage(run.dir), "stage: write file")

  test("stage with explicit commitMessage uses it verbatim (no agent call)"):
    val prompts = ConcurrentLinkedQueue[String]()
    withRun(TextReplyingAgent("should not appear", prompts)): run =>
      import run.given
      val _ = stage[String](
        "write file",
        commitMessage = Some(_ => "explicit: my message")
      ):
        os.write.over(run.dir / "seed.txt", "modified by stage")
        "done"
      assertEquals(lastCommitMessage(run.dir), "explicit: my message")
      assert(prompts.isEmpty, "the explicit-message path must not call a model")

  test("a large stage diff reaches the model bounded, with the --stat summary"):
    val prompts = ConcurrentLinkedQueue[String]()
    withRun(TextReplyingAgent("Rewrite seed file", prompts)): run =>
      import run.given
      val _ = stage("write file"):
        os.write.over(
          run.dir / "seed.txt",
          (1 to 20000).map(i => s"line $i").mkString("\n")
        )
        "done"
      val prompt = nextPrompt(prompts)
      // The payload, not the whole prompt: its size is the contract, and the
      // instructions above it are free to change.
      val payload = prompt.drop(prompt.indexOf("Files changed:"))
      // Bounded, but not to a bare stat: the budget is spent on the diff head,
      // which reaches a few hundred lines in and stops well before the end.
      assert(clue(payload.length) <= BoundedDiff.CommitThreshold)
      assert(clue(payload.length) > BoundedDiff.CommitThreshold - 64)
      assert(prompt.contains("file changed"), "the --stat summary is missing")
      assert(prompt.contains("\n+line 300\n"), "the diff head is missing")
      assert(!prompt.contains("+line 5000"), "the diff was not truncated")
      assert(prompt.contains("…(truncated)"), "the cut went unmarked")

  test("a stage whose only change is a new file still describes it"):
    // An untracked file has no tracked history to diff against, but the stage's
    // `add -A` commit includes it — so the model has to see it too.
    val prompts = ConcurrentLinkedQueue[String]()
    withRun(TextReplyingAgent("Add ignore rules", prompts)): run =>
      import run.given
      val _ = stage("add file"):
        os.write(run.dir / ".gitignore", "target/\n")
        "done"
      val prompt = nextPrompt(prompts)
      assert(prompt.contains("New files:\n.gitignore"), prompt)
      assert(prompt.contains("+target/"), "the new file's contents are missing")
      assertEquals(lastCommitMessage(run.dir), "Add ignore rules")

  test("a later stage's prompt excludes the .orca progress log"):
    val prompts = ConcurrentLinkedQueue[String]()
    withRun(TextReplyingAgent("Update seed", prompts)): run =>
      import run.given
      val _ = stage("first"):
        os.write.over(run.dir / "seed.txt", "first change")
        "done"
      val _ = stage("second"):
        os.write.over(run.dir / "seed.txt", "second change")
        // A mid-body session-store write, to pin that the stage diff carries
        // nothing from `.orca/` — the log the first stage committed included.
        run.control.sessionStore.upsert(
          SessionRecord(
            name = "s",
            stage = StagePath.FlowBody,
            id = "sid",
            seed = "seed",
            resumeWireId = Some("wire"),
            backend = BackendTag.ClaudeCode
          )
        )
        "done"
      val _ = nextPrompt(prompts)
      val second = nextPrompt(prompts)
      assert(!second.contains(".orca"), second)
      assert(second.contains("seed.txt"), "the real change is missing")

  test(
    "stage with no commitMessage and blank agent reply falls back to stage:<name>"
  ):
    withRun(TextReplyingAgent("   ")): run =>
      import run.given
      val _ = stage("write file"):
        os.write.over(run.dir / "seed.txt", "modified by stage")
        "done"
      assertEquals(lastCommitMessage(run.dir), "stage: write file")
