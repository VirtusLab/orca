package orca.tools.codex

import orca.llm.{AutoApprove, BackendTag, LlmConfig, SessionId}

/** Maps `LlmConfig` fields to `codex exec` CLI flags. `systemPrompt` is not
  * handled here — codex doesn't accept an `--append-system-prompt` equivalent
  * on `exec`, so the backend folds it into the user prompt before this method
  * runs. `onUnapproved` and `retrySchedule` have no CLI shape and live at the
  * orchestrator layer.
  *
  * codex exec is one-shot: each call processes one prompt and exits. Multi-turn
  * happens via `codex exec resume <thread_id>`. We expose both shapes via
  * [[exec]] / [[execResume]].
  */
private[codex] object CodexArgs:

  /** Single-turn `codex exec --json [<prompt>]` invocation. */
  def exec(
      prompt: String,
      config: LlmConfig,
      outputSchemaFile: Option[os.Path],
      workDir: os.Path
  ): Seq[String] =
    Seq("codex", "exec", "--json") ++
      sandboxArgs(config) ++
      modelArgs(config) ++
      cwdArgs(workDir) ++
      // --skip-git-repo-check is permissive — codex bails if it can't
      // tell whether cwd is a git repo, which is a poor fit for tests
      // and one-off invocations against arbitrary directories.
      Seq("--skip-git-repo-check") ++
      outputSchemaArgs(outputSchemaFile) ++
      Seq(prompt)

  /** Multi-turn continuation: `codex exec resume <id> <prompt>`.
    *
    * Two limitations vs. [[exec]]:
    *   - `exec resume` doesn't accept `--cd / -C`, so cwd is set on the OS
    *     process spawn rather than the argv.
    *   - `exec resume` doesn't accept `--output-schema`, so the resumed turn's
    *     structured-output validation falls to the prompt template + the
    *     post-hoc parser. The retry-with- corrective-prompt loop in
    *     `DefaultLlmCall` handles parse failures.
    *
    * codex also enforces that the resumed session was not started with
    * `--ephemeral`; the backend never passes `--ephemeral` on `exec`, so resume
    * always finds a rollout.
    */
  def execResume(
      sessionId: SessionId[BackendTag.Codex.type],
      prompt: String,
      config: LlmConfig
  ): Seq[String] =
    Seq("codex", "exec", "resume", "--json", SessionId.value(sessionId)) ++
      sandboxArgs(config) ++
      modelArgs(config) ++
      Seq("--skip-git-repo-check") ++
      Seq(prompt)

  private def modelArgs(config: LlmConfig): Seq[String] =
    config.model.toSeq.flatMap(m => Seq("--model", m))

  private def cwdArgs(workDir: os.Path): Seq[String] =
    Seq("-C", workDir.toString)

  /** codex's structured-output gate. Unlike claude, the schema is passed by
    * file path rather than inline, so the backend writes the schema string to
    * disk first and hands us the resolved path.
    */
  private def outputSchemaArgs(file: Option[os.Path]): Seq[String] =
    file.toSeq.flatMap(p => Seq("--output-schema", p.toString))

  /** Approval-policy mapping. codex doesn't accept a per-tool allowlist on the
    * CLI, so [[AutoApprove.Only]] is approximated with `--full-auto` (sandboxed
    * automatic execution) — narrower than the all-bypass and matches the
    * user-stated intent of "auto-approve a known-safe set".
    *
    *   - `AutoApprove.All` → `--dangerously-bypass-approvals-and-sandbox`
    *   - `AutoApprove.Only(_)` → `--full-auto`
    */
  private def sandboxArgs(config: LlmConfig): Seq[String] =
    config.autoApprove match
      case AutoApprove.All => Seq("--dangerously-bypass-approvals-and-sandbox")
      case AutoApprove.Only(_) => Seq("--full-auto")
