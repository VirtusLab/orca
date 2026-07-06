package orca.runner

import orca.agents.{AutoApprove, Enforcement, ToolSet}
import orca.backend.AgentBackend
import orca.subprocess.StubCliRunner
import orca.tools.claude.ClaudeBackend
import orca.tools.codex.CodexBackend
import orca.tools.gemini.GeminiBackend
import orca.tools.opencode.OpencodeBackend
import orca.tools.pi.PiBackend

import ox.channels.BufferCapacity
import ox.supervised

/** Machine-checked source of truth for the per-backend enforcement matrix (the
  * `Enforcement` a `(ToolSet, AutoApprove)` combination gets on each backend).
  * The rendered table lives in `AGENTS.md`; this test is what keeps it honest.
  *
  * Every backend's `enforcement` is a pure function delegating to its
  * `*Args.enforcement`, so construction is cheap: a [[StubCliRunner]] the
  * method never touches. opencode's server is lazy, so a `httpFor` that throws
  * is never evaluated.
  */
class EnforcementTableTest extends munit.FunSuite:

  private val onlyEmpty: AutoApprove = AutoApprove.Only(Set.empty)
  private val onlySome: AutoApprove = AutoApprove.Only(Set("Read"))

  test("every backend maps (tools, autoApprove) to the documented enforcement"):
    supervised:
      given BufferCapacity = BufferCapacity(8)
      val cli = new StubCliRunner()
      val backends: List[(String, AgentBackend[?])] = List(
        "claude" -> new ClaudeBackend(cli),
        "codex" -> new CodexBackend(cli),
        "gemini" -> new GeminiBackend(cli),
        "opencode" -> new OpencodeBackend(_ =>
          throw new AssertionError("enforcement must not touch the server")
        ),
        "pi" -> new PiBackend(cli)
      )
      def get(name: String): AgentBackend[?] =
        backends.collectFirst { case (n, b) if n == name => b }.get

      import Enforcement.*
      import AutoApprove.All
      // (backend, tools, autoApprove, expected)
      val cases: List[(String, ToolSet, AutoApprove, Enforcement)] = List(
        // ReadOnly, * — hard no-edit on every backend.
        ("claude", ToolSet.ReadOnly, All, Hard),
        ("codex", ToolSet.ReadOnly, All, Hard),
        ("gemini", ToolSet.ReadOnly, All, Hard),
        ("opencode", ToolSet.ReadOnly, All, Hard),
        ("pi", ToolSet.ReadOnly, All, Hard),
        // NetworkOnly, * — codex/pi grant network via a writable shell.
        ("claude", ToolSet.NetworkOnly, All, Hard),
        ("codex", ToolSet.NetworkOnly, All, PromptOnly),
        ("gemini", ToolSet.NetworkOnly, All, Hard),
        ("opencode", ToolSet.NetworkOnly, All, Hard),
        ("pi", ToolSet.NetworkOnly, All, PromptOnly),
        // Full, All
        ("claude", ToolSet.Full, All, Hard),
        ("codex", ToolSet.Full, All, Hard),
        ("gemini", ToolSet.Full, All, Hard),
        ("opencode", ToolSet.Full, All, Ignored),
        ("pi", ToolSet.Full, All, Ignored),
        // Full, Only(_)
        ("claude", ToolSet.Full, onlySome, Hard),
        ("codex", ToolSet.Full, onlySome, SandboxApprox),
        ("gemini", ToolSet.Full, onlySome, Ignored),
        ("opencode", ToolSet.Full, onlySome, Ignored),
        ("pi", ToolSet.Full, onlySome, Ignored),
        // Full, Only(empty) — same tier as Only(_).
        ("claude", ToolSet.Full, onlyEmpty, Hard),
        ("codex", ToolSet.Full, onlyEmpty, SandboxApprox),
        ("gemini", ToolSet.Full, onlyEmpty, Ignored),
        ("opencode", ToolSet.Full, onlyEmpty, Ignored),
        ("pi", ToolSet.Full, onlyEmpty, Ignored)
      )
      // Collect every mismatched cell and fail once with the full list, so one
      // run surfaces all divergences rather than stopping at the first.
      val mismatches = cases.flatMap: (name, tools, approve, expected) =>
        val actual = get(name).enforcement(tools, approve)
        Option.when(actual != expected)(
          s"$name / $tools / $approve: expected $expected, got $actual"
        )
      assert(
        mismatches.isEmpty,
        s"enforcement matrix mismatches:\n${mismatches.mkString("\n")}"
      )
