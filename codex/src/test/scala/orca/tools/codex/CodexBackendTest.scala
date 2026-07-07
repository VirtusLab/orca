package orca.tools.codex

import orca.backend.SupervisedBackend
import orca.agents.{BackendTag, AgentConfig, Model, SessionId, WireSessionId}
import orca.{OrcaFlowException}
import orca.subprocess.{FakePipedCliProcess, SpawnStubCliRunner}

class CodexBackendTest extends munit.FunSuite:

  private def clientSid: SessionId[BackendTag.Codex.type] =
    SessionId[BackendTag.Codex.type]("00000000-0000-0000-0000-000000000000")

  private def withBackend[T](runner: SpawnStubCliRunner)(
      body: ox.Ox ?=> CodexBackend => T
  ): T = SupervisedBackend.using(new CodexBackend(runner))(body)

  private def successfulProcess(
      threadId: String = "thr-test",
      message: String = "hello world",
      inputTokens: Long = 10L,
      outputTokens: Long = 5L
  ): FakePipedCliProcess =
    val p = new FakePipedCliProcess()
    p.enqueueStdout(s"""{"type":"thread.started","thread_id":"$threadId"}""")
    p.enqueueStdout(s"""{"type":"turn.started"}""")
    p.enqueueStdout(
      s"""{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"$message"}}"""
    )
    p.enqueueStdout(
      s"""{"type":"turn.completed","usage":{"input_tokens":$inputTokens,"output_tokens":$outputTokens,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    p.closeStdout()
    p.closeStderr()
    p.sendSigInt() // mark exited so waitForExit returns
    p

  test("runAutonomous parses thread id, last agent_message, and usage"):
    val runner = new SpawnStubCliRunner(
      List(successfulProcess("thr-42", "the answer", 100L, 25L))
    )
    withBackend(runner): backend =>
      val result =
        backend.runAutonomous(
          "q",
          clientSid,
          AgentConfig(),
          os.temp.dir()
        )
      // The result reports the WIRE id — the server-minted thr-42 — while the
      // client→server mapping is recorded in the registry so subsequent calls
      // resume it without the caller threading a new id back in.
      assertEquals(
        result.wireId,
        WireSessionId[BackendTag.Codex.type]("thr-42")
      )
      assertEquals(result.output, "the answer")
      assertEquals(result.usage.inputTokens, 100L)
      assertEquals(result.usage.outputTokens, 25L)

  test("runAutonomous surfaces the model id reported on thread.started"):
    val p = new FakePipedCliProcess()
    p.enqueueStdout(
      """{"type":"thread.started","thread_id":"thr-m","model":"gpt-5"}"""
    )
    p.enqueueStdout("""{"type":"turn.started"}""")
    p.enqueueStdout(
      """{"type":"item.completed","item":{"id":"i","type":"agent_message","text":"hi"}}"""
    )
    p.enqueueStdout(
      """{"type":"turn.completed","usage":{"input_tokens":1,"output_tokens":1,"cached_input_tokens":0,"reasoning_output_tokens":0}}"""
    )
    p.closeStdout()
    p.closeStderr()
    p.sendSigInt()
    withBackend(new SpawnStubCliRunner(List(p))): backend =>
      val result =
        backend.runAutonomous(
          "q",
          clientSid,
          AgentConfig(),
          os.temp.dir()
        )
      assertEquals(result.model, Some(Model("gpt-5")))

  test("runAutonomous throws when codex exits without turn.completed"):
    val p = new FakePipedCliProcess()
    p.enqueueStdout("""{"type":"thread.started","thread_id":"thr-x"}""")
    p.closeStdout()
    p.closeStderr()
    p.sendSigInt()
    withBackend(new SpawnStubCliRunner(List(p))): backend =>
      intercept[OrcaFlowException]:
        backend.runAutonomous(
          "q",
          clientSid,
          AgentConfig(),
          os.temp.dir()
        )

  test("runAutonomous throws with the exit code when codex exits non-zero"):
    val p = new FakePipedCliProcess(initiallyAlive = false):
      override def tryExitCode: Option[Int] = Some(7)
    p.closeStdout()
    p.closeStderr()
    withBackend(new SpawnStubCliRunner(List(p))): backend =>
      val ex = intercept[OrcaFlowException]:
        backend.runAutonomous(
          "q",
          clientSid,
          AgentConfig(),
          os.temp.dir()
        )
      assert(
        ex.getMessage.contains("exited with code 7"),
        s"expected the exit code in the failure message; got: ${ex.getMessage}"
      )

  test("non-zero exit attaches buffered stderr to the exception message"):
    val p = new FakePipedCliProcess(initiallyAlive = false):
      override def tryExitCode: Option[Int] = Some(7)
    p.enqueueStderr("Error: thread/resume failed: not found")
    p.closeStdout()
    p.closeStderr()
    withBackend(new SpawnStubCliRunner(List(p))): backend =>
      val ex = intercept[OrcaFlowException]:
        backend.runAutonomous(
          "q",
          clientSid,
          AgentConfig(),
          os.temp.dir()
        )
      assert(
        ex.getMessage.contains("thread/resume failed: not found"),
        s"expected stderr in the exception; got: ${ex.getMessage}"
      )

  test("`Reading additional input from stdin` stderr noise is filtered"):
    val p = new FakePipedCliProcess(initiallyAlive = false):
      override def tryExitCode: Option[Int] = Some(7)
    p.enqueueStderr("Reading additional input from stdin")
    p.closeStdout()
    p.closeStderr()
    withBackend(new SpawnStubCliRunner(List(p))): backend =>
      val ex = intercept[OrcaFlowException]:
        backend.runAutonomous(
          "q",
          clientSid,
          AgentConfig(),
          os.temp.dir()
        )
      assert(
        !ex.getMessage.contains("Reading additional input from stdin"),
        s"filtered noise leaked into the exception: ${ex.getMessage}"
      )

  test("systemPrompt is folded into the user prompt as a preamble"):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      val _ = backend.runAutonomous(
        "list files",
        clientSid,
        AgentConfig().copy(systemPrompt = Some("be terse")),
        os.temp.dir()
      )
      val args = runner.calls.head
      val finalPrompt = args.last
      assert(finalPrompt.contains("be terse"))
      assert(finalPrompt.contains("list files"))

  test(
    "second runAutonomous call with the same client id resumes the mapped server thread"
  ):
    val runner = new SpawnStubCliRunner(
      List(
        successfulProcess("thr-server-1"),
        successfulProcess("thr-server-1")
      )
    )
    withBackend(runner): backend =>
      val workDir = os.temp.dir()
      val _ =
        backend.runAutonomous("first", clientSid, AgentConfig(), workDir)
      val _ =
        backend.runAutonomous("again", clientSid, AgentConfig(), workDir)
      val firstArgs = runner.calls(0)
      val secondArgs = runner.calls(1)
      assert(!firstArgs.contains("resume"), firstArgs)
      // Second call routes through `codex exec resume … <server-id>` — the
      // server id was learned from the first call's thread.started.
      assert(secondArgs.contains("resume"), secondArgs)
      assert(secondArgs.contains("thr-server-1"), secondArgs)

  test(
    "registerSession after an interactive call lets a follow-up autonomous call resume"
  ):
    // Codex's server thread id is learned inside the conversation drain
    // (not at spawn time), so the framework calls `registerSession`
    // post-drain to record the client→server mapping. Pin that mechanism:
    // once registered, a subsequent autonomous call with the same client id
    // routes through `codex exec resume <server-id>`, not a fresh `exec`.
    val runner = new SpawnStubCliRunner(
      List(
        successfulProcess("thr-via-interactive"),
        successfulProcess("thr-via-interactive")
      )
    )
    withBackend(runner): backend =>
      val workDir = os.temp.dir()
      // Simulate the post-interactive-drain registration that DefaultAgentCall
      // performs (this test exercises the backend in isolation; the
      // integration path is wired in AgentCall.runInteractiveOnce).
      backend.sessions.register(
        clientSid,
        WireSessionId[BackendTag.Codex.type]("thr-via-interactive")
      )
      val _ =
        backend.runAutonomous("after", clientSid, AgentConfig(), workDir)
      val args = runner.calls.head
      assert(args.contains("resume"), args)
      assert(args.contains("thr-via-interactive"), args)

  test("distinct client ids both start fresh — no cross-client mapping"):
    // Pins the per-client isolation of the session registry: a different
    // client id must NOT resume the prior call's server thread.
    val runner = new SpawnStubCliRunner(
      List(
        successfulProcess("thr-server-A"),
        successfulProcess("thr-server-B")
      )
    )
    withBackend(runner): backend =>
      val workDir = os.temp.dir()
      val sidA =
        SessionId[BackendTag.Codex.type]("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
      val sidB =
        SessionId[BackendTag.Codex.type]("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
      val _ = backend.runAutonomous("for A", sidA, AgentConfig(), workDir)
      val _ = backend.runAutonomous("for B", sidB, AgentConfig(), workDir)
      val secondArgs = runner.calls(1)
      assert(
        !secondArgs.contains("resume"),
        s"second call with a new client id must NOT resume; got: $secondArgs"
      )

  /** Pulls the `--output-schema <path>` value out of a recorded argv, as an
    * `os.Path`. Fails the test if the flag isn't present.
    */
  private def schemaPathFrom(args: Seq[String]): os.Path =
    os.Path(
      args
        .zip(args.tail)
        .collectFirst { case ("--output-schema", v) => v }
        .getOrElse(fail(s"--output-schema not found in: $args"))
    )

  test(
    "runAutonomous writes the output schema to a temp file outside workDir, and removes it once the turn finalizes"
  ):
    // Autonomous structured calls (reviewers) get codex-side schema
    // enforcement: the drain needs `conv.outputSchema` set so it suppresses
    // the raw JSON payload, and `--output-schema` adds codex-side
    // validation on top of the prompt template. The file must live OUTSIDE
    // workDir — a fixed workDir-relative path would race the parallel
    // reviewer fan-out and get swept into the flow's `git add -A` — and must
    // not survive the call, or long flows would accumulate orphan schema
    // files.
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      val workDir = os.temp.dir()
      val _ = backend.runAutonomous(
        "q",
        clientSid,
        AgentConfig(),
        workDir,
        outputSchema = Some("""{"type":"object"}""")
      )
      val schemaFile = schemaPathFrom(runner.calls.head)
      assert(
        !schemaFile.startsWith(workDir),
        s"schema file must live outside workDir; got: $schemaFile"
      )
      assert(
        schemaFile.last.startsWith("orca-codex-schema-"),
        s"expected the orca-codex-schema- temp-file prefix; got: $schemaFile"
      )
      assertEquals(
        os.list(workDir).toList,
        Nil,
        "nothing should be written under workDir for a structured call"
      )
      // runAutonomous drains the conversation to completion synchronously, so
      // by the time it returns, `onFinalize` has already run and removed the
      // temp file.
      assert(
        !os.exists(schemaFile),
        "schema temp file should be deleted once the turn finalizes"
      )

  test(
    "two structured autonomous calls each get their own schema file (no race on a shared path)"
  ):
    val runner = new SpawnStubCliRunner(
      List(successfulProcess("thr-1"), successfulProcess("thr-2"))
    )
    withBackend(runner): backend =>
      val workDir = os.temp.dir()
      val schema = Some("""{"type":"object"}""")
      val sidA =
        SessionId[BackendTag.Codex.type]("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
      val sidB =
        SessionId[BackendTag.Codex.type]("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
      val _ = backend.runAutonomous(
        "a",
        sidA,
        AgentConfig(),
        workDir,
        outputSchema = schema
      )
      val _ = backend.runAutonomous(
        "b",
        sidB,
        AgentConfig(),
        workDir,
        outputSchema = schema
      )
      val schemaFileA = schemaPathFrom(runner.calls(0))
      val schemaFileB = schemaPathFrom(runner.calls(1))
      assert(
        schemaFileA != schemaFileB,
        s"concurrent/successive structured calls must not share a schema path; got: $schemaFileA and $schemaFileB"
      )

  test(
    "runAutonomous does NOT pass -c mcp_servers (autonomous skips the bridge)"
  ):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      val _ =
        backend.runAutonomous(
          "q",
          clientSid,
          AgentConfig(),
          os.temp.dir()
        )
      val args = runner.calls.head
      assert(
        !args.exists(_.startsWith("mcp_servers.")),
        s"autonomous must not register an MCP server; got: $args"
      )

  test(
    "runInteractive writes the output schema to a temp file outside the workdir"
  ):
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      val workDir = os.temp.dir()
      val _ = backend.runInteractive(
        "q",
        clientSid,
        displayPrompt = "q",
        AgentConfig(),
        workDir,
        Some("""{"type":"object"}""")
      )
      // Unlike runAutonomous, runInteractive hands back a Conversation the
      // test never drains, so `onFinalize` hasn't fired yet — the file is
      // still there to inspect.
      val schemaFile = schemaPathFrom(runner.calls.head)
      assert(
        !schemaFile.startsWith(workDir),
        s"schema file must live outside workDir; got: $schemaFile"
      )
      assert(os.exists(schemaFile))
      assertEquals(os.read(schemaFile), """{"type":"object"}""")
      assertEquals(os.list(workDir).toList, Nil)

  test(
    "runInteractive registers an MCP server and folds the ask_user hint"
  ):
    // Interactive mode stands up the ask_user bridge: codex sees the MCP
    // server via `-c mcp_servers.orca.url=…`, and the agent sees a short
    // hint about the tool in the system-prompt preamble (codex has no
    // --append-system-prompt, so it's folded into the user prompt).
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      val _ = backend.runInteractive(
        "q",
        clientSid,
        displayPrompt = "q",
        AgentConfig(),
        os.temp.dir(),
        outputSchema = None
      )
      val args = runner.calls.head
      val mcpFlag = args.zip(args.tail).collectFirst {
        case ("-c", v) if v.startsWith("mcp_servers.orca.url=") => v
      }
      assert(
        mcpFlag.isDefined,
        s"interactive must register the orca MCP server; got: $args"
      )
      val finalPrompt = args.last
      assert(
        finalPrompt.contains("ask_user"),
        s"final prompt should fold in the ask_user hint; got: $finalPrompt"
      )

  test(
    "runInteractive with a systemPrompt folds BOTH it and the ask_user hint"
  ):
    // The 4-way combination of (systemPrompt, askUserHint) is concat-prone;
    // pin the both-present case explicitly (the other three are exercised
    // by adjacent tests: (None,false) via runAutonomous, (Some,false) via
    // "systemPrompt is folded", (None,true) via the test above).
    val runner = new SpawnStubCliRunner(List(successfulProcess()))
    withBackend(runner): backend =>
      val _ = backend.runInteractive(
        "list files",
        clientSid,
        displayPrompt = "list files",
        AgentConfig().copy(systemPrompt = Some("be terse")),
        os.temp.dir(),
        outputSchema = None
      )
      val finalPrompt = runner.calls.head.last
      assert(finalPrompt.contains("be terse"))
      assert(finalPrompt.contains("ask_user"))
      // The two pieces must be separated, not concatenated.
      val terseIdx = finalPrompt.indexOf("be terse")
      val askIdx = finalPrompt.indexOf("ask_user")
      assert(
        terseIdx < askIdx && askIdx - terseIdx > "be terse".length + 2,
        s"systemPrompt and ask_user hint should be separated; got: $finalPrompt"
      )

  test(
    "sessionExists is registry-gated: true when the mapped SERVER id has a rollout file"
  ):
    // Codex mints its own thread id; a rollout file is named with that SERVER
    // id, never the client id. `exists` resolves client→server via the registry
    // (rehydrated from the log on resume) and probes THAT id.
    val serverId = "test-session-id-123"
    val tmpSessions = os.temp.dir()
    os.write(tmpSessions / s"rollout-2024-01-01-$serverId.jsonl", "")
    SupervisedBackend.using(
      new CodexBackend(new SpawnStubCliRunner(Nil), tmpSessions)
    ): backend =>
      backend.sessions.register(
        clientSid,
        WireSessionId[BackendTag.Codex.type](serverId)
      )
      assert(backend.sessions.exists(clientSid))

  test("sessionExists returns false when there is no client→server mapping"):
    // No registration: the client id resolves to no server id, so the probe
    // never runs — even if a rollout file happens to be named with the client
    // id (which codex never does in practice).
    val tmpSessions = os.temp.dir()
    os.write(
      tmpSessions / s"rollout-2024-01-01-${SessionId.value(clientSid)}.jsonl",
      ""
    )
    SupervisedBackend.using(
      new CodexBackend(new SpawnStubCliRunner(Nil), tmpSessions)
    ): backend =>
      assert(!backend.sessions.exists(clientSid))

  test("sessionExists returns false when no matching file exists"):
    val tmpSessions = os.temp.dir()
    SupervisedBackend.using(
      new CodexBackend(new SpawnStubCliRunner(Nil), tmpSessions)
    ): backend =>
      backend.sessions.register(
        clientSid,
        WireSessionId[BackendTag.Codex.type]("thr-server-1")
      )
      assert(!backend.sessions.exists(clientSid))

  test("sessionExists returns false when the sessions dir is absent"):
    val missing = os.temp.dir() / "no-such-sessions"
    SupervisedBackend.using(
      new CodexBackend(new SpawnStubCliRunner(Nil), missing)
    ): backend =>
      backend.sessions.register(
        clientSid,
        WireSessionId[BackendTag.Codex.type]("thr-server-1")
      )
      assert(!backend.sessions.exists(clientSid))

  test(
    "sessionExists returns false for a mapped SERVER id `.*` even when rollout files exist (blocks regex injection)"
  ):
    val tmpSessions = os.temp.dir()
    // Create a rollout file that the old regex `rollout-.*-.*\.jsonl` would match
    os.write(tmpSessions / "rollout-2024-01-01-some-real-id.jsonl", "")
    SupervisedBackend.using(
      new CodexBackend(new SpawnStubCliRunner(Nil), tmpSessions)
    ): backend =>
      // The malicious id is the resolved WIRE id; `register`'s SessionId.isSafe
      // guard must refuse to record it in the first place, so `exists` never
      // even reaches the walk (no mapping to resolve).
      backend.sessions.register(
        clientSid,
        WireSessionId[BackendTag.Codex.type](".*")
      )
      assert(!backend.sessions.exists(clientSid))
