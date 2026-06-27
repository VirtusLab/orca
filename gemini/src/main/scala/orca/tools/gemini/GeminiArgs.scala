package orca.tools.gemini

import orca.backend.CliArgs
import orca.agents.{AutoApprove, BackendTag, AgentConfig, SessionId, ToolSet}

/** Maps `AgentConfig` fields to `gemini` headless CLI flags. `systemPrompt` is
  * not handled here — gemini has no `--append-system-prompt` equivalent (it
  * picks up `GEMINI.md` files for static instructions), so the backend folds it
  * into the user prompt before this method runs. The `ask_user` MCP server is
  * registered via a project-local `.gemini/settings.json` merge in the backend
  * rather than an argv flag (gemini has no inline MCP override like codex's
  * `-c`).
  *
  * gemini headless is one-shot: each `-p` invocation processes one prompt and
  * exits. Multi-turn happens via `--resume <session-id>`, where the id is the
  * one surfaced on the `init` event of a prior `stream-json` run. We expose
  * both shapes via [[headless]] / [[resume]].
  */
private[gemini] object GeminiArgs:

  /** Single-turn `gemini -p <prompt> --output-format stream-json` invocation.
    * cwd is set on the OS process spawn (gemini headless has no `-C` flag), so
    * it isn't rendered here.
    */
  def headless(prompt: String, config: AgentConfig): Seq[String] =
    Seq("gemini") ++
      trustArgs ++
      CliArgs.modelArgs(config) ++
      approvalArgs(config) ++
      Seq("--output-format", "stream-json", "-p", prompt)

  /** Multi-turn continuation: `gemini --resume <id> -p <prompt>`. The id is the
    * session id learned from the prior run's `init` event.
    */
  def resume(
      sessionId: SessionId[BackendTag.Gemini.type],
      prompt: String,
      config: AgentConfig
  ): Seq[String] =
    Seq("gemini") ++
      trustArgs ++
      CliArgs.modelArgs(config) ++
      approvalArgs(config) ++
      Seq("--resume", SessionId.value(sessionId)) ++
      Seq("--output-format", "stream-json", "-p", prompt)

  /** gemini refuses to run headless in a folder it doesn't consider "trusted"
    * (exit 55) and, worse, silently overrides `--approval-mode` back to
    * `default` before failing. orca always drives a working directory the agent
    * is meant to operate in, so trust is unconditional — the direct analog of
    * codex's `--skip-git-repo-check`. (Equivalent to setting
    * `GEMINI_CLI_TRUST_WORKSPACE=true`; the flag keeps it visible at the call
    * site.)
    */
  private val trustArgs: Seq[String] = Seq("--skip-trust")

  /** Web tools pre-approved on [[ToolSet.NetworkOnly]] turns. Plan mode gates
    * `web_fetch` behind an approval no autonomous turn can answer; listing it
    * in `--allowed-tools` pre-approves it (verified), so the planner gets web
    * reads while plan mode still blocks edits and shell — a hard no-edit
    * guarantee like claude's. `--allowed-tools` is deprecated (gemini 1.0
    * removes it for a `settings.json` Policy Engine); migrate then.
    */
  private val NetworkTools: Seq[String] = Seq("web_fetch")

  /** Maps [[AgentConfig.tools]] to gemini's approval mode. The read-only tiers
    * use `--approval-mode plan` (no writes, no shelling out), matching claude's
    * `--permission-mode plan` and codex's `--sandbox read-only`. `Full` has no
    * per-tool CLI allowlist, and in headless mode `auto_edit` blocks on shell
    * approvals no one can answer, so both [[AutoApprove.All]] and
    * [[AutoApprove.Only]] map to `yolo`. The `Only` widening is in ADR 0015.
    *
    *   - `ReadOnly` → `--approval-mode plan`
    *   - `NetworkOnly` → `--approval-mode plan --allowed-tools <web tools>`
    *   - `Full` + `AutoApprove.All` / `Only(_)` → `--approval-mode yolo`
    *
    * `NetworkOnly` stays in plan mode (hard no-edit) and adds [[NetworkTools]]
    * so the planner can fetch issue/PR/web content — no shell `gh`, so no
    * authed GitHub, but web reads work.
    */
  private def approvalArgs(config: AgentConfig): Seq[String] =
    config.tools match
      case ToolSet.ReadOnly => Seq("--approval-mode", "plan")
      case ToolSet.NetworkOnly =>
        Seq(
          "--approval-mode",
          "plan",
          "--allowed-tools",
          NetworkTools.mkString(",")
        )
      case ToolSet.Full =>
        config.autoApprove match
          case AutoApprove.All | AutoApprove.Only(_) =>
            Seq("--approval-mode", "yolo")
