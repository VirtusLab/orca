package orca.tools.opencode

/** How orca launches the shared `opencode serve` process. orca always appends
  * `serve --port <n> --log-level WARN` to the prefix below.
  *
  *   - [[default]] runs the `opencode` binary directly.
  *   - [[ollama]] wraps it in `ollama launch opencode --model <model> --`, so
  *     the spawned server inherits Ollama's generated provider config (injected
  *     via `OPENCODE_CONFIG_CONTENT`) — the zero-config path for local Ollama
  *     models, instead of hand-writing that config into `opencode.json`.
  *
  * Select it per flow: `flow(OrcaArgs(args), opencodeLauncher =
  * OpencodeLauncher.ollama("qwen3-coder"))`.
  */
opaque type OpencodeLauncher = Seq[String]

object OpencodeLauncher:
  /** Run `opencode` directly. */
  val default: OpencodeLauncher = Seq("opencode")

  /** `ollama launch opencode --model <model> --` — injects Ollama's provider
    * config for `model` and makes it the server's **default**, so a bare
    * `opencode` turn routes to it with no `withModel` call. The model must
    * already be pulled (`ollama pull <model>`) and the `ollama` CLI on PATH.
    * `--model` is required: `ollama launch` otherwise falls back to interactive
    * model selection, which fails in orca's headless server context.
    *
    * The launcher pins exactly this one model — the spawned server declares
    * only it and rejects any other Ollama model id — so switching models means
    * relaunching with a different `ollama(...)`, not `withModel`.
    */
  def ollama(model: String): OpencodeLauncher =
    Seq("ollama", "launch", "opencode", "--model", model, "--")

  /** A custom launch prefix; orca appends `serve …` after it. Include a
    * trailing `--` if your wrapper needs one to separate its own flags from
    * opencode's (as `ollama launch` does).
    */
  def apply(prefix: Seq[String]): OpencodeLauncher = prefix

  extension (l: OpencodeLauncher) private[opencode] def prefix: Seq[String] = l
