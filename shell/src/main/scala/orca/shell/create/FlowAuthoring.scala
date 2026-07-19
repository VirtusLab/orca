package orca.shell.create

import orca.OrcaDir
import orca.agents.BackendTag
import orca.settings.{GlobalSettings, SettingsFile, SettingsScope}
import orca.shell.ShellVersion
import orca.util.PromptResource
import ox.discard

import scala.util.control.NonFatal

/** Where a new flow is saved, and the cwd the authoring harness launches from
  * (ADR 0021 §9): Project saves under the workdir's committed `.orca/flows/`
  * and launches from `workDir` itself; Global saves under the config-home
  * `flows/` dir and launches from its parent — the config-home `orca/` dir
  * (`~/.config/orca` by default) — so both the extracted API material and the
  * flow file itself land inside the harness's workspace (gemini hard-fails,
  * claude/opencode prompt for approval, on an out-of-workspace path).
  */
private[shell] enum CreateTier:
  case Project, Global

/** The new flow's target path and the cwd its authoring harness launches from.
  */
private[shell] case class CreateTarget(flowPath: os.Path, cwd: os.Path)

/** Harness argv for the create-a-flow session, plus a prompt to print and have
  * the user paste in when the harness can't be handed one on its own argv (see
  * [[FlowAuthoring.harnessArgv]]).
  */
private[shell] case class HarnessLaunch(
    argv: Seq[String],
    pastePrompt: Option[String]
)

/** Creates a new flow with a harness's help (ADR 0021 §9): extracts the bundled
  * API material into the harness's workspace, builds the initial prompt, and
  * resolves the harness's launch argv. The menu wiring itself
  * (target-tier/filename/goal/harness prompts, the actual `exec`) lives in
  * `Main`.
  */
private[shell] object FlowAuthoring:

  private val resourcePrefix = "/orca/shell/api/"

  /** The bundled files' basenames, matching the resource-generator's copy
    * (build.sbt) — the README plus the two example flows used as few-shot
    * material.
    */
  private val bundledNames =
    List("README.md", "implement.sc", "implement-interactive.sc")

  /** Ensures a `.sc` suffix on a user-supplied filename. */
  def normalizedFileName(raw: String): String =
    if raw.endsWith(".sc") then raw else s"$raw.sc"

  /** Words dropped from [[localFilenameSlug]]'s slug — common enough to appear in
    * almost any goal sentence without saying anything about the flow itself.
    */
  private val slugStopwords =
    Set("a", "an", "the", "to", "for", "and", "of", "in", "on", "with")

  private val slugWordCount = 4

  /** Proposes a `.sc` filename from a goal description, kebab-casing its first
    * few meaningful words: lowercase, split on runs of non-letter/non-digit
    * characters (unicode-aware, so accented input splits the same as plain
    * ASCII), drop [[slugStopwords]], keep the first [[slugWordCount]]
    * survivors, join with `-`. A goal that yields no words at all (empty,
    * punctuation-only, or entirely stopwords) falls back to `new-flow.sc`
    * rather than an empty name. This is the local, no-LLM fallback
    * [[suggestFilename]] degrades to when the cheap slug-suggestion harness
    * call is slow, absent, or unreachable; either way the result is offered to
    * the user as an editable default (`ui.input`'s existing default-hint path),
    * never written unconfirmed.
    */
  def localFilenameSlug(goal: String): String =
    val words = goal
      .toLowerCase(java.util.Locale.ROOT)
      .split("[^\\p{L}\\p{N}]+")
      .filter(_.nonEmpty)
      .filterNot(slugStopwords.contains)
      .take(slugWordCount)
    val base = if words.isEmpty then "new-flow" else words.mkString("-")
    s"$base.sc"

  /** Fork target's default filename: the source's basename minus `.sc`, plus
    * `-fork.sc` — offered the same editable way as [[localFilenameSlug]].
    */
  def forkFilenameDefault(sourceName: String): String =
    val base =
      if sourceName.endsWith(".sc") then sourceName.dropRight(3)
      else sourceName
    s"$base-fork.sc"

  /** Non-interactive, one-shot-and-exit invocation per backend for the cheap
    * slug-suggestion call ([[suggestFilename]]), verified against each
    * installed CLI's `--help`: claude's `-p`/`--print`, codex's `exec`
    * subcommand, gemini's `-p`/`--prompt`, opencode's `run` subcommand, and
    * pi's `-p`/`--print` — all distinct from [[harnessArgv]]'s interactive
    * session forms.
    */
  def slugArgv(backend: BackendTag, prompt: String): Seq[String] =
    backend match
      case BackendTag.ClaudeCode => Seq("claude", "-p", prompt)
      case BackendTag.Codex      => Seq("codex", "exec", prompt)
      case BackendTag.Pi         => Seq("pi", "-p", prompt)
      case BackendTag.Gemini     => Seq("gemini", "-p", prompt)
      case BackendTag.Opencode   => Seq("opencode", "run", prompt)

  /** The cheap slug-suggestion prompt: asks for nothing but a bare filename, so
    * [[sanitizeSlug]] has the best chance of a clean answer to sanitize.
    */
  def slugPrompt(goal: String): String =
    s"""Suggest a short lowercase-kebab-case filename (letters, digits, and
       |hyphens only, no file extension, no explanation) for a script whose
       |goal is:
       |$goal
       |
       |Reply with ONLY the filename, nothing else.""".stripMargin

  private val maxSlugLength = 50

  private def toKebab(text: String): String =
    text
      .toLowerCase(java.util.Locale.ROOT)
      .replaceAll("[^a-z0-9]+", "-")
      .replaceAll("-{2,}", "-")
      .stripPrefix("-")
      .stripSuffix("-")

  /** Sanitizes arbitrary text — a harness's raw slug reply, or anything else —
    * into a valid flow filename: lowercase kebab-case, letters/digits/hyphens
    * only, length-bounded, `.sc`-suffixed. Falls back to `new-flow.sc` when
    * nothing survives (an empty string, or one that's pure punctuation/
    * whitespace).
    */
  def sanitizeSlug(raw: String): String =
    val stripped = toKebab(raw.stripSuffix(".sc"))
    val bounded =
      if stripped.length > maxSlugLength then
        toKebab(stripped.take(maxSlugLength))
      else stripped
    if bounded.isEmpty then "new-flow.sc" else normalizedFileName(bounded)

  private val slugTimeoutMillis = 4000L

  /** Best-effort filename suggestion for a new flow — the "cheap slug prompt":
    * runs `backend` non-interactively on [[slugPrompt]] (via
    * [[slugArgv]]) with a short timeout, sanitizes its last non-blank output
    * line through [[sanitizeSlug]], and falls back to [[localFilenameSlug]]'s
    * local word-based derivation whenever the harness is unreachable, too slow,
    * exits non-zero, or replies with nothing [[sanitizeSlug]] can turn into
    * more than `new-flow.sc`. This is a nicety layered on top of a
    * fully-working local fallback, never something the caller blocks on
    * indefinitely. `runner` is injected for testing — a stub returning canned
    * stdout, or `None` to simulate an unreachable/timed-out harness — and
    * defaults to a real, timeout-bounded [[os.proc]] spawn ([[runSlugProc]]).
    */
  def suggestFilename(
      backend: BackendTag,
      goal: String,
      timeoutMillis: Long = slugTimeoutMillis,
      runner: (Seq[String], Long) => Option[String] = runSlugProc
  ): String =
    val lastLine =
      runner(slugArgv(backend, slugPrompt(goal)), timeoutMillis).toList
        .flatMap(_.linesIterator.map(_.trim))
        .filter(_.nonEmpty)
        .lastOption
    lastLine.map(sanitizeSlug) match
      case Some(slug) if slug != "new-flow.sc" => slug
      case _                                   => localFilenameSlug(goal)

  /** The configured coding-role agent from the global settings file, falling
    * back to claude when the file is absent or unparseable — the same fallback
    * the wizard uses for an undetected default. Shared by the harness picker's
    * preselect and [[suggestFilenameForGoal]]'s slug call.
    */
  def configuredCodingAgent(globalSettingsPath: os.Path): BackendTag =
    Option
      .when(os.exists(globalSettingsPath))(os.read(globalSettingsPath))
      .flatMap(content =>
        SettingsFile.parse(content, SettingsScope.UserGlobal).toOption
      )
      .flatMap(_.agents.coding)
      .map(_.backend)
      .getOrElse(BackendTag.ClaudeCode)

  /** The new flow's filename suggestion (the cheap slug prompt): runs the
    * configured coding agent — not the harness picked later in the same
    * create-flow attempt — non-interactively via [[suggestFilename]], falling
    * back to its own local word-based derivation within a few seconds if that
    * harness is slow, absent, or unreachable.
    */
  def suggestFilenameForGoal(goal: String): String =
    suggestFilename(configuredCodingAgent(GlobalSettings.default), goal)

  /** Runs `argv` to completion within `timeoutMillis`, returning its stdout on
    * a zero exit; `None` on a timeout (the process is killed rather than left
    * running), a non-zero exit, or any failure to even start (a missing binary
    * throws from `os.proc`'s spawn). [[suggestFilename]]'s production runner.
    */
  private def runSlugProc(
      argv: Seq[String],
      timeoutMillis: Long
  ): Option[String] =
    try
      val proc =
        os.proc(argv).spawn(stdin = os.Pipe, stdout = os.Pipe, stderr = os.Pipe)
      try
        if proc.waitFor(timeoutMillis) && proc.exitCode() == 0 then
          Some(proc.stdout.text())
        else None
      finally if proc.isAlive() then destroyQuietly(proc)
    catch case NonFatal(_) => None

  /** Force-kills `proc` (`destroy(shutdownGracePeriod = 0)` — the
    * non-deprecated spelling of `destroyForcibly()`), with stdout muted for the
    * call itself. os-lib 0.11.4's `SubProcess.destroy` unconditionally
    * `println`s `"wrapped.destroyForcibly()"` to the real stdout right before
    * actually forcibly killing (an upstream debug leftover, not gated behind
    * any flag) — on this timeout path that noise would otherwise land on the
    * user's terminal mid-prompt. The subprocess's own stdout was captured via
    * `os.Pipe` above, not `Console.out`, so muting it here can't drop any of
    * its output.
    */
  private def destroyQuietly(proc: os.SubProcess): Unit =
    val mutedOut = java.io.PrintStream(java.io.OutputStream.nullOutputStream())
    Console.withOut(mutedOut)(proc.destroy(shutdownGracePeriod = 0))

  /** Pure path arithmetic for the tier choice (ADR 0021 §9) — no I/O, so
    * unit-testable without touching a real filesystem. `globalFlows` is
    * `GlobalSettings.defaultFlows` (or a test double), matching
    * [[orca.shell.flows.FlowEditor.customizeTarget]]'s convention of taking the
    * resolved path rather than re-deriving it from env/home here.
    */
  def resolveTarget(
      tier: CreateTier,
      fileName: String,
      workDir: os.Path,
      globalFlows: os.Path
  ): CreateTarget =
    val name = normalizedFileName(fileName)
    tier match
      case CreateTier.Project =>
        CreateTarget(OrcaDir.flowsPath(workDir) / name, workDir)
      case CreateTier.Global =>
        val configOrca = globalFlows / os.up
        CreateTarget(globalFlows / name, configOrca)

  /** [[resolveTarget]] plus the side effects the menu wiring needs before
    * launching: ensuring the tier's flows dir exists, then refusing on a
    * filename collision — the harness itself writes the flow file, so a
    * pre-existing file at the target path is never intended to be overwritten.
    */
  def prepareTarget(
      tier: CreateTier,
      fileName: String,
      workDir: os.Path,
      globalFlows: os.Path
  ): Either[String, CreateTarget] =
    val target = resolveTarget(tier, fileName, workDir, globalFlows)
    tier match
      case CreateTier.Project => OrcaDir.ensureFlows(workDir).discard
      case CreateTier.Global  => os.makeDir.all(globalFlows)
    if os.exists(target.flowPath) then
      Left(s"${target.flowPath} already exists — pick a different name")
    else Right(target)

  /** Extracts the bundled README + two example flows into
    * `<cacheBase>/orca-api-<version>/`, returning that directory. `cacheBase`
    * is the already-ensured cache base — `OrcaDir.ensureCache(workDir)` for a
    * project flow, `<config-home orca dir>/cache` for a global one (ADR 0021
    * §9). Idempotency key: the directory holding all three bundled names,
    * mirroring [[orca.shell.flows.BuiltInFlows]]'s completeness check.
    *
    * Simpler than `BuiltInFlows`' whole-directory temp-dir-then-move: this
    * material is three small, static files with no per-version content rewrite
    * (the prompt states the running version verbatim, so a stale pin baked into
    * the bundled examples is cosmetic, not load-bearing). Each file is still
    * written via a same-directory temp-file-then-move ([[writeAtomically]]) so
    * a process killed mid-write can never leave a truncated file at its final
    * name looking complete — only a whole-file miss is possible, which the
    * completeness check catches and retries.
    */
  def extractApiMaterial(cacheBase: os.Path, version: String): os.Path =
    val dir = cacheBase / s"orca-api-$version"
    if !isComplete(dir) then
      os.makeDir.all(dir)
      bundledNames.foreach: name =>
        writeAtomically(dir / name, PromptResource.load(resourcePrefix + name))
    dir

  private def isComplete(dir: os.Path): Boolean =
    os.isDir(dir) && bundledNames.forall(name => os.isFile(dir / name))

  /** Writes `content` to `path` via a same-directory temp file plus `os.move`,
    * so a process killed mid-write leaves only the (never looked at again) temp
    * file behind — never a truncated `path`. Mirrors
    * `BuiltInFlows.materialize`'s atomic-move fallback for a filesystem without
    * atomic rename support.
    */
  private def writeAtomically(path: os.Path, content: String): Unit =
    val tmp = path / os.up / s".${path.last}.tmp"
    os.write.over(tmp, content)
    try os.move(tmp, path, replaceExisting = true, atomicMove = true)
    catch
      case _: java.nio.file.AtomicMoveNotSupportedException =>
        os.move(tmp, path, replaceExisting = true)

  /** The initial prompt handed to the authoring harness (ADR 0021 §9): the goal
    * and target path, the verbatim
    * version-pinned header to start the file with, the line-1 `//` description
    * convention, pointers to the extracted README/examples, the `scala-cli
    * compile` verification step, the runtime-vs-compile-time rules caveat, and
    * — last resort only — the tag-pinned raw README URL. Kept in one place
    * since the prompt text is itself the deliverable.
    *
    * On a non-release `orcaVersion` (a dev build's `"dev"`, or a dynver
    * snapshot) the plain `//> using dep` pin doesn't resolve from Maven
    * Central, so the header also gets `//> using repository ivy2Local` right
    * after it — the same treatment `BuiltInFlows`/`_seed_lib.sh --local` apply
    * — so the prompt's own `scala-cli compile` instruction stays honest on a
    * local build.
    */
  def initialPrompt(
      goal: String,
      targetPath: os.Path,
      apiDir: os.Path,
      orcaVersion: String
  ): String =
    val readme = apiDir / "README.md"
    val example1 = apiDir / "implement.sc"
    val example2 = apiDir / "implement-interactive.sc"
    val ivy2LocalLine =
      if ShellVersion.isRelease(orcaVersion) then ""
      else "\n//> using repository ivy2Local"
    // The goal now comes from a multiline prompt (inputMultiline), so it's
    // indented as its own block rather than trailing "Goal: " on one line —
    // keeps a multi-paragraph goal visually distinct from the rest of the
    // prompt instead of running the first line on with the label.
    val indentedGoal = indentBlock(goal)
    // The "3.8.4" literal in the header below is kept in lockstep with
    // V.scala in project/Dependencies.scala by hand — updateDocs only
    // rewrites .md/.sc files, so this prompt text is invisible to it.
    s"""Write a new Orca flow at $targetPath.
       |
       |Goal:
       |$indentedGoal
       |
       |Start the file with this exact header (the pinned version matches the
       |orca release this session was launched from):
       |//> using scala 3.8.4
       |//> using dep "org.virtuslab::orca:$orcaVersion"$ivy2LocalLine
       |//> using jvm 21
       |
       |Line 1 of the file must be a `//` comment giving a one-line description
       |of the flow — the shell's flow listing uses it as the description.
       |
       |The Orca API reference is at $readme — read it before writing the
       |flow. Two example flows are at $example1 and $example2; start from
       |whichever is closer to the goal.
       |
       |After writing the file, verify it with `scala-cli compile $targetPath`
       |and fix errors until it compiles.
       |
       |Caveat: some authoring rules (fork-boundary captures, stage
       |push-after-commit ordering, no concurrent stages) are enforced at runtime,
       |not by the compiler — a script can compile and still violate them.
       |Follow the README's Authoring rules section beyond what the compiler
       |catches.
       |
       |Last resort, only if the local README above is somehow missing: the
       |tag-pinned reference is at
       |https://raw.githubusercontent.com/VirtusLab/orca/v$orcaVersion/README.md
       |""".stripMargin

  /** Two-space-indents every line of `text` — the shared block-quoting used by
    * [[initialPrompt]]'s goal and [[forkPrompt]]'s change description, both of
    * which come from `inputMultiline` and so may be several lines long.
    */
  private def indentBlock(text: String): String =
    text.linesIterator.map(line => s"  $line").mkString("\n")

  /** The path the authoring harness should be told to read the fork's source
    * flow from: `sourcePath` itself when it already sits inside `cwd` (the
    * harness's launch workspace) — true for a Project-tier source forked to a
    * Project-tier target (both under `workDir`), and for a Global-tier source
    * forked to a Global-tier target (`cwd` is `globalFlows`'s parent, so
    * `globalFlows/name.sc` is still inside it). Verified false in every other
    * case: a cross-tier fork (Project source into a Global target or vice
    * versa) puts the source under the *other* tier's directory, outside `cwd`;
    * a BuiltIn source lives under `BuiltInFlows.extracted`'s cache directory
    * (`$XDG_CACHE_HOME/orca/shell/<version>/flows`), which is never inside
    * either tier's workspace regardless of the fork's target tier.
    *
    * In every such case, copies `sourcePath` into `apiDir` (already inside the
    * workspace, alongside the extracted README/examples) under its own basename
    * and returns that copy instead — so the harness (gemini in particular,
    * which hard-fails on an out-of-workspace path; claude/opencode otherwise
    * prompt for approval) can always read the fork's source without a workspace
    * escape. The copy is written once per basename: a repeat call (re-running
    * create-flow against the same apiDir) leaves an existing copy as-is rather
    * than re-copying over it.
    */
  def resolveForkSource(
      sourcePath: os.Path,
      sourceName: String,
      cwd: os.Path,
      apiDir: os.Path
  ): os.Path =
    if sourcePath.startsWith(cwd) then sourcePath
    else
      val copy = apiDir / sourceName
      if !os.exists(copy) then os.copy(sourcePath, copy, replaceExisting = true)
      copy

  /** The initial prompt for a fork session (ADR 0021 §9): states the
    * source path and the described changes, instructs copying the source to the
    * target path verbatim before applying them, and otherwise mirrors
    * [[initialPrompt]]'s API-reference pointers, compile-check step, and
    * runtime-rules caveat. `sourcePath` is whatever [[resolveForkSource]]
    * resolved — a path already known-readable from the harness's workspace.
    */
  def forkPrompt(
      changes: String,
      sourcePath: os.Path,
      targetPath: os.Path,
      apiDir: os.Path,
      orcaVersion: String
  ): String =
    val readme = apiDir / "README.md"
    val example1 = apiDir / "implement.sc"
    val example2 = apiDir / "implement-interactive.sc"
    val indentedChanges = indentBlock(changes)
    s"""Fork the Orca flow at $sourcePath into a new flow at $targetPath.
       |
       |First copy $sourcePath to $targetPath verbatim, then apply these
       |changes:
       |$indentedChanges
       |
       |Keep the copied file's existing version-pinned header (`//> using
       |scala`/`//> using dep`/`//> using jvm`) and its line-1 `//`
       |one-line-description convention — update the description line only if
       |the fork's behavior changes enough to make the original one wrong.
       |
       |The Orca API reference is at $readme — read it if the changes need API
       |surface the source doesn't already use. Two example flows are at
       |$example1 and $example2.
       |
       |After writing the file, verify it with `scala-cli compile $targetPath`
       |and fix errors until it compiles.
       |
       |Caveat: some authoring rules (fork-boundary captures, stage
       |push-after-commit ordering, no concurrent stages) are enforced at runtime,
       |not by the compiler — a script can compile and still violate them.
       |Follow the README's Authoring rules section beyond what the compiler
       |catches.
       |
       |Last resort, only if the local README above is somehow missing: the
       |tag-pinned reference is at
       |https://raw.githubusercontent.com/VirtusLab/orca/v$orcaVersion/README.md
       |""".stripMargin

  /** Harness argv for the create-a-flow session, verified against each
    * installed CLI's `--help` (July 2026) rather than assumed:
    *
    *   - claude: `claude <prompt>` — a positional prompt starts an interactive
    *     session with it submitted as the first message (`claude --help`:
    *     "starts an interactive session by default"; the prompt argument is
    *     separate from `-p/--print`, which is the non-interactive mode).
    *   - codex: `codex <prompt>` — `codex --help`: `[PROMPT]` "Optional user
    *     prompt to start the session" on the bare (non-subcommand) form, which
    *     is the interactive TUI.
    *   - pi: `pi <prompt>` — `pi --help`'s own examples list `pi "List all .ts
    *     files in src/"` under "Interactive mode with initial prompt".
    *   - gemini: `gemini -i <prompt>` — `-i/--prompt-interactive`: "Execute the
    *     provided prompt and continue in interactive mode" (the bare positional
    *     `query` also seeds interactive mode, but `-i` is the documented,
    *     unambiguous flag for it; `-p/--prompt` is the *non-interactive*
    *     headless mode, a different flag entirely).
    *   - opencode: no auto-submitting form exists. The default TUI command does
    *     accept `--prompt <text>`, but it only PREFILLS the input box — it does
    *     not submit it (confirmed: an open opencode feature request, "Add a TUI
    *     flag/endpoint that auto-submits the initial prompt", asks for exactly
    *     this because today's `--prompt` doesn't). So this launches bare and
    *     returns the prompt for the caller to print with a "paste this into the
    *     agent" instruction instead of relying on a flag that would leave the
    *     user unsure whether anything was submitted.
    *
    * `yolo`, when true, appends each CLI's own no-approval-prompts flag
    * (verified against each installed CLI's `--help`): claude's
    * `--dangerously-skip-permissions`, codex's
    * `--dangerously-bypass-approvals-and-sandbox`, gemini's `-y`/`--yolo`. pi
    * has no approval gate to bypass (nothing to append; [[yoloCaveat]] notes
    * this) and opencode's interactive TUI has no such flag at all — only its
    * headless `opencode run` subcommand does — so opencode's argv is unchanged
    * regardless of `yolo` ([[yoloCaveat]] notes that too).
    */
  def harnessArgv(
      backend: BackendTag,
      prompt: String,
      yolo: Boolean
  ): HarnessLaunch =
    backend match
      case BackendTag.ClaudeCode =>
        val flag = if yolo then Seq("--dangerously-skip-permissions") else Nil
        HarnessLaunch(Seq("claude", prompt) ++ flag, None)
      case BackendTag.Codex =>
        val flag =
          if yolo then Seq("--dangerously-bypass-approvals-and-sandbox")
          else Nil
        HarnessLaunch(Seq("codex", prompt) ++ flag, None)
      case BackendTag.Pi => HarnessLaunch(Seq("pi", prompt), None)
      case BackendTag.Gemini =>
        val flag = if yolo then Seq("--yolo") else Nil
        HarnessLaunch(Seq("gemini", "-i", prompt) ++ flag, None)
      case BackendTag.Opencode =>
        HarnessLaunch(Seq("opencode"), Some(prompt))

  /** A one-line note to print when `yolo` was requested but the backend can't
    * honor it via argv — `None` for every backend that either doesn't need one
    * (`yolo` false) or already got its flag appended in [[harnessArgv]]. Kept
    * separate from [[harnessArgv]] (which stays a pure argv builder) since this
    * is display-only text for the caller to print, not part of the launch
    * command.
    */
  def yoloCaveat(backend: BackendTag, yolo: Boolean): Option[String] =
    if !yolo then None
    else
      backend match
        case BackendTag.Pi =>
          Some("pi has no approval gate to bypass — nothing to change.")
        case BackendTag.Opencode =>
          Some(
            "opencode has no interactive yolo flag — approvals are controlled by opencode.jsonc's `permission` field."
          )
        case _ => None
