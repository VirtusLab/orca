package orca.tools.opencode

/** The launch prefix for the shared `opencode serve` process; orca appends
  * `serve --port <n> --log-level WARN`.
  *
  *   - [[default]] runs the `opencode` binary directly.
  *   - [[ollama]] wraps it to inject Ollama's generated provider config — the
  *     zero-config path for local Ollama models.
  *
  * Select it per flow via the `opencode` agent-override factory: `flow(
  * OrcaArgs(args), opencode = Some(w => OpencodeAgents.default(w,
  * OpencodeLauncher.ollama("qwen3-coder"))))`.
  */
opaque type OpencodeLauncher = Seq[String]

object OpencodeLauncher:
  /** Run `opencode` directly. */
  val default: OpencodeLauncher = Seq("opencode")

  /** `ollama launch opencode --model <model> --` — injects Ollama's provider
    * config for `model` and makes it the server's **default**, so a bare
    * `opencode` turn routes to it with no `withModel` call. The model must
    * already be pulled and the `ollama` CLI on PATH. `--model` is required:
    * `ollama launch` otherwise falls back to interactive selection, which fails
    * in orca's headless context.
    *
    * The launcher pins exactly this one model — the server rejects any other
    * Ollama id — so switching models means relaunching, not `withModel`.
    */
  def ollama(model: String): OpencodeLauncher =
    Seq("ollama", "launch", "opencode", "--model", model, "--")

  /** A custom launch prefix; orca appends `serve …` after it. Include a
    * trailing `--` if your wrapper needs one to separate its own flags from
    * opencode's (as `ollama launch` does).
    */
  def apply(prefix: Seq[String]): OpencodeLauncher = prefix

  extension (l: OpencodeLauncher) private[opencode] def prefix: Seq[String] = l
