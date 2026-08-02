package orca.shell.create

import orca.OrcaDir
import orca.agents.BackendTag
import orca.settings.{AgentSpec, GlobalSettings, SettingsFile, SettingsScope}
import orca.shell.ShellVersion
import orca.util.PromptResource
import ox.discard

import scala.util.control.NonFatal

/** Where a new flow is saved (ADR 0021 §9): Project saves under the workdir's
  * committed `.orca/flows/`; Global saves under the config-home `flows/` dir.
  * `cwd` (see [[CreateTarget]]) is the tier's associated directory — its parent
  * for Global — used to place the extracted API material and to judge
  * fork-source proximity, independent of the authoring flow's own launch
  * `workDir` (always the project repo it was run from).
  */
private[shell] enum CreateTier:
  case Project, Global

/** The new flow's target path, plus its tier's associated directory (see
  * [[CreateTier]]'s scaladoc).
  */
private[shell] case class CreateTarget(flowPath: os.Path, cwd: os.Path)

/** Creates a new flow by authoring it through the built-in `simple.sc` flow
  * (ADR 0021 §9): extracts the bundled API material, builds the initial prompt.
  * The menu wiring itself (target-tier/filename/goal prompts) lives in `Main`;
  * the flow launch lives in `orca.shell.actions.AuthorAction`.
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

  /** Words dropped from [[localFilenameSlug]]'s slug — common enough to appear
    * in almost any goal sentence without saying anything about the flow itself.
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
    * pi's `-p`/`--print`. Claude gets `--model haiku` — a slug needs the
    * cheapest tier, and the user's default (often opus) would double the
    * latency; the other CLIs keep their own configured default, since their
    * cheap-model names are provider- or install-specific.
    */
  def slugArgv(backend: BackendTag, prompt: String): Seq[String] =
    val binary = AgentSpec.harnessNameFor(backend)
    backend match
      case BackendTag.ClaudeCode =>
        Seq(binary, "-p", "--model", "haiku", prompt)
      case BackendTag.Codex    => Seq(binary, "exec", prompt)
      case BackendTag.Pi       => Seq(binary, "-p", prompt)
      case BackendTag.Gemini   => Seq(binary, "-p", prompt)
      case BackendTag.Opencode => Seq(binary, "run", prompt)

  /** The cheap slug-suggestion prompt: asks for nothing but a bare filename, so
    * [[sanitizeSlug]] has the best chance of a clean answer to sanitize.
    */
  def slugPrompt(goal: String): String =
    s"""Suggest a short lowercase-kebab-case filename (letters, digits, and
       |hyphens only, no file extension, no explanation) for a script whose
       |goal is:""".stripMargin + "\n" + goal +
      "\n\nReply with ONLY the filename, nothing else."

  /** [[slugPrompt]]'s fork counterpart: the same bare-filename ask, grounded in
    * the source flow's name and one-line description plus the user's described
    * changes, so the suggestion names what the FORK does differently rather
    * than restating the source's own goal. `sourceDescription` is whatever the
    * caller already read off the source file (e.g. `DiscoveredFlow.description`
    * — [[orca.shell.flows.FlowDescription]]'s line-1 `//` convention), not
    * re-read here.
    */
  def forkSlugPrompt(
      sourceName: String,
      sourceDescription: Option[String],
      changes: String
  ): String =
    val description = sourceDescription.getOrElse("(no description)")
    s"""Suggest a short lowercase-kebab-case descriptor (1-3 words; letters,
       |digits, and hyphens only; no file extension, no explanation) for the
       |change a fork makes to an existing script. The descriptor will be
       |appended to the source script's filename.
       |
       |Source script: $sourceName
       |Source description: $description
       |
       |Changes made in the fork:""".stripMargin + "\n" + changes +
      "\n\nReply with ONLY the descriptor, nothing else."

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

  // A real `claude -p --model haiku` call measures ~11s cold; 4s guaranteed
  // the local fallback always won.
  private val slugTimeoutMillis = 20000L

  /** Shared engine behind [[suggestFilename]] and [[suggestFilenameForFork]]:
    * runs `backend` non-interactively on `prompt` (via [[slugArgv]]) with a
    * short timeout, sanitizes its last non-blank output line through
    * [[sanitizeSlug]], and falls back to `fallback` whenever the harness is
    * unreachable, too slow, exits non-zero, or replies with nothing
    * [[sanitizeSlug]] can turn into more than `new-flow.sc`. This is a nicety
    * layered on top of a fully-working local fallback, never something the
    * caller blocks on indefinitely. `runner` is injected for testing — a stub
    * returning canned stdout, or `None` to simulate an unreachable/timed-out
    * harness — and defaults to a real, timeout-bounded [[os.proc]] spawn
    * ([[runSlugProc]]).
    */
  private def runSlugSuggestion(
      backend: BackendTag,
      prompt: String,
      fallback: => String,
      timeoutMillis: Long,
      runner: (Seq[String], Long) => Option[String]
  ): String =
    val lastLine =
      runner(slugArgv(backend, prompt), timeoutMillis).toList
        .flatMap(_.linesIterator.map(_.trim))
        .filter(_.nonEmpty)
        .lastOption
    lastLine.map(sanitizeSlug) match
      case Some(slug) if slug != "new-flow.sc" => slug
      case _                                   => fallback

  /** Best-effort filename suggestion for a new flow — the "cheap slug prompt"
    * ([[runSlugSuggestion]]) over [[slugPrompt]], falling back to
    * [[localFilenameSlug]]'s local word-based derivation.
    */
  def suggestFilename(
      backend: BackendTag,
      goal: String,
      timeoutMillis: Long = slugTimeoutMillis,
      runner: (Seq[String], Long) => Option[String] = runSlugProc
  ): String =
    runSlugSuggestion(
      backend,
      slugPrompt(goal),
      localFilenameSlug(goal),
      timeoutMillis,
      runner
    )

  /** The configured coding-role agent spec (harness + model pin) from the
    * global settings file, `None` when it's absent or unparseable. Backs
    * [[configuredCodingAgent]].
    */
  private def configuredCodingAgentSpec(
      globalSettingsPath: os.Path
  ): Option[AgentSpec] =
    Option
      .when(os.exists(globalSettingsPath))(os.read(globalSettingsPath))
      .flatMap(content =>
        SettingsFile.parse(content, SettingsScope.UserGlobal).toOption
      )
      .flatMap(_.agents.coding)

  /** The configured coding-role harness, falling back to claude when the global
    * settings file is absent or unparseable — the same fallback the wizard uses
    * for an undetected default. Used by [[suggestFilenameForGoal]]'s and
    * [[suggestFilenameForFork]]'s slug calls — the cheap filename suggestion
    * always runs on the configured coding agent, not a harness choice
    * (authoring itself no longer has one).
    */
  def configuredCodingAgent(globalSettingsPath: os.Path): BackendTag =
    configuredCodingAgentSpec(globalSettingsPath)
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

  /** The fork target's filename: `<source-stem>-<descriptor>.sc`, where the
    * descriptor is the cheap harness call's answer to [[forkSlugPrompt]]. The
    * source's identity is guaranteed structurally — the composition always
    * starts from the source's own stem, so the reply can only ever shape the
    * suffix (a reply that repeats the stem is deduplicated). Same never-blocks
    * contract as [[suggestFilenameForGoal]]; an unusable or slow reply falls
    * back to [[forkFilenameDefault]]. `timeoutMillis`/`runner` are exposed so
    * tests can stub the harness call.
    */
  def suggestFilenameForFork(
      sourceName: String,
      sourceDescription: Option[String],
      changes: String,
      timeoutMillis: Long = slugTimeoutMillis,
      runner: (Seq[String], Long) => Option[String] = runSlugProc
  ): String =
    val stem = toKebab(sourceName.stripSuffix(".sc"))
    val descriptor = runner(
      slugArgv(
        configuredCodingAgent(GlobalSettings.default),
        forkSlugPrompt(sourceName, sourceDescription, changes)
      ),
      timeoutMillis
    ).toList
      .flatMap(_.linesIterator.map(_.trim))
      .filter(_.nonEmpty)
      .lastOption
      .map(reply => toKebab(reply.stripSuffix(".sc")))
      .map(_.stripPrefix(stem).stripPrefix("-"))
      .filter(d => d.nonEmpty && d != "fork")
    descriptor match
      case Some(d) => sanitizeSlug(s"$stem-$d")
      case None    => forkFilenameDefault(sourceName)

  /** Runs `argv` to completion within `timeoutMillis`, returning its stdout on
    * a zero exit; `None` on a timeout (the process tree is killed — os-lib's
    * `destroy` recurses into `children()`, so the agent CLI's own subprocesses
    * go with it — rather than left running), a non-zero exit, or any failure to
    * even start (a missing binary throws from `os.proc`'s spawn).
    * [[suggestFilename]]'s production runner.
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
      finally if proc.isAlive() then proc.destroy(shutdownGracePeriod = 0)
    catch case NonFatal(_) => None

  /** The directory associated with `tier` (see [[CreateTier]]'s scaladoc):
    * `workDir` for Project, `globalFlows`'s parent for Global. Shared by
    * [[resolveTarget]] and `AuthorAction`'s edit-by-agent overwrite path, which
    * uses it only to build a well-formed `CreateTarget.cwd` — the same shape
    * every other tier target carries — for its `overwrite`d `CreateTarget`.
    * `AuthorAction.fork` resolves the fork source against the SANDBOX, not this
    * value, so no [[resolveForkSource]] proximity check is wired through it
    * here.
    */
  def tierCwd(
      tier: CreateTier,
      workDir: os.Path,
      globalFlows: os.Path
  ): os.Path =
    tier match
      case CreateTier.Project => workDir
      case CreateTier.Global  => globalFlows / os.up

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
    val cwd = tierCwd(tier, workDir, globalFlows)
    tier match
      case CreateTier.Project =>
        CreateTarget(OrcaDir.flowsPath(workDir) / name, cwd)
      case CreateTier.Global => CreateTarget(globalFlows / name, cwd)

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

  /** [[prepareTarget]] with an auto-derived, collision-free filename (ADR 0021
    * §9): keeps `baseName` when the target is free, else appends `-2`, `-3`, …
    * before the `.sc` suffix. Authoring never asks for a filename, so a taken
    * name is uniquified rather than refused.
    */
  def prepareAutoTarget(
      tier: CreateTier,
      baseName: String,
      workDir: os.Path,
      globalFlows: os.Path
  ): CreateTarget =
    val normalized = normalizedFileName(baseName)
    val stem = normalized.stripSuffix(".sc")
    LazyList
      .from(1)
      .map(n => if n == 1 then normalized else s"$stem-$n.sc")
      .flatMap(name => prepareTarget(tier, name, workDir, globalFlows).toOption)
      .head

  /** A flow filename is documented as a bare filename, not a path — rejects one
    * containing a path separator (`../escape.sc`, `sub/dir.sc`) with a clean
    * usage error up front, before it ever reaches [[prepareTarget]]'s path
    * arithmetic (which, for a name with enough `..`s, os-lib can reject by
    * throwing a raw `PathError` instead of returning one). Shared by the CLI's
    * `create`/`fork` (`AuthorCli`) and the interactive shell's new-flow/fork
    * prompts (`Main.promptFlowTarget`) — either path must re-prompt/re-report
    * on an invalid name rather than crash or silently escape the target
    * directory.
    */
  def validateFileName(fileName: String): Either[String, Unit] =
    Either.cond(
      !fileName.contains("/") && !fileName.contains("\\"),
      (),
      s"'$fileName' isn't a valid flow filename — path separators aren't allowed"
    )

  /** [[prepareTarget]], with any exception os-lib's path arithmetic throws for
    * a filename that survived [[validateFileName]] but still drives it outside
    * the filesystem root (e.g. `os.PathError.AbsolutePathOutsideRoot` — every
    * `os.PathError` variant is a plain `IllegalArgumentException`, there's no
    * shared marker type to catch) converted to a clean `Left` instead of
    * propagating as an uncaught exception.
    */
  def safePrepareTarget(
      tier: CreateTier,
      fileName: String,
      workDir: os.Path,
      globalFlows: os.Path
  ): Either[String, CreateTarget] =
    try prepareTarget(tier, fileName, workDir, globalFlows)
    catch
      case _: IllegalArgumentException =>
        Left(s"'$fileName' isn't a valid flow filename")

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

  /** The authoring task handed to the built-in `simple.sc` flow as its
    * `userPrompt` (ADR 0021 §9): the goal and target path, the verbatim
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
  /** The exact `//> using` header lines a new flow file starts with (the
    * "3.8.4"/`21` literals are kept in lockstep with `V.scala` in
    * `project/Dependencies.scala` by hand — `updateDocs` only rewrites
    * `.md`/`.sc` files, so this text is invisible to it). Non-release builds (a
    * dynver snapshot, or the bare `"dev"`) add `//> using repository ivy2Local`
    * right after the dep pin — the same treatment `BuiltInFlows`/`_seed_lib.sh
    * --local` apply — so `scala-cli compile` resolves against the local build
    * instead of failing against Maven Central. Shared by [[initialPrompt]]
    * (states it as an instruction to the authoring agent) and [[skeletonFlow]]
    * (writes it verbatim), so a hand-written skeleton and an agent-authored
    * file pin the same way.
    */
  private def versionPinLines(orcaVersion: String): String =
    val ivy2LocalLine =
      if ShellVersion.isRelease(orcaVersion) then ""
      else "\n//> using repository ivy2Local"
    s"""//> using scala 3.8.4
       |//> using dep "org.virtuslab::orca:$orcaVersion"$ivy2LocalLine
       |//> using jvm 21""".stripMargin

  def initialPrompt(
      goal: String,
      targetPath: os.Path,
      apiDir: os.Path,
      orcaVersion: String
  ): String =
    val readme = apiDir / "README.md"
    val example1 = apiDir / "implement.sc"
    val example2 = apiDir / "implement-interactive.sc"
    // The goal now comes from a multiline prompt (inputMultiline), so it's
    // indented as its own block rather than trailing "Goal: " on one line —
    // keeps a multi-paragraph goal visually distinct from the rest of the
    // prompt instead of running the first line on with the label.
    s"Write a new Orca flow at $targetPath.\n\nGoal:\n" + indentBlock(goal) +
      "\n\n" +
      s"""Start the file with this exact header (the pinned version matches the
         |orca release this flow was launched from):
         |${versionPinLines(orcaVersion)}
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
    * which come from `inputMultiline` and so may be several lines long. Callers
    * concatenate the result: interpolating it into a `stripMargin` block would
    * eat the leading `|` of a table or margin block the user typed.
    */
  private def indentBlock(text: String): String =
    text.linesIterator.map(line => s"  $line").mkString("\n")

  /** The path the authoring prompt should point the coding session at for the
    * fork's source flow: `sourcePath` itself when it already sits inside `cwd`
    * — true for a Project-tier source forked to a Project-tier target (both
    * under `workDir`), and for a Global-tier source forked to a Global-tier
    * target (`cwd` is `globalFlows`'s parent, so `globalFlows/name.sc` is still
    * inside it). False in every other case: a cross-tier fork (Project source
    * into a Global target or vice versa) puts the source under the *other*
    * tier's directory; a BuiltIn source lives under `BuiltInFlows.extracted`'s
    * cache directory (`$XDG_CACHE_HOME/orca/shell/<version>/flows`), outside
    * either tier entirely.
    *
    * In every such case, copies `sourcePath` into `apiDir` (alongside the
    * extracted README/examples) under its own basename and returns that copy
    * instead, so the prompt only ever names one directory's worth of reference
    * material. The copy is written once per basename: a repeat call (re-running
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

  /** The shared tail of the fork/edit authoring task — API-reference pointers,
    * the compile-check step, the runtime-rules caveat, and the last-resort
    * README URL — appended after `opening` states what to do and to which
    * paths. Shared by [[forkPrompt]] and [[editPrompt]] so the two prompts,
    * which differ only in how they describe the action (copy-then-change vs.
    * edit-in-place), can't drift on everything else.
    */
  private def changePrompt(
      opening: String,
      targetPath: os.Path,
      apiDir: os.Path,
      orcaVersion: String
  ): String =
    val readme = apiDir / "README.md"
    val example1 = apiDir / "implement.sc"
    val example2 = apiDir / "implement-interactive.sc"
    // `opening` already carries the user's typed changes: interpolating it here
    // would run a second `stripMargin` pass over that text.
    opening + "\n\n" +
      s"""The Orca API reference is at $readme — read it if the changes need API
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

  /** The authoring task for a fork (ADR 0021 §9): states the source path and
    * the described changes as one indivisible step (create the target by
    * copying the source and applying the changes — not two separate
    * deliverables, since there's no planner here to split them and no reviewer
    * should treat the copy as its own reviewable unit). `sourcePath` is
    * whatever [[resolveForkSource]] resolved. The rest — API pointers,
    * compile-check, caveat — is [[changePrompt]]'s shared tail.
    */
  def forkPrompt(
      changes: String,
      sourcePath: os.Path,
      targetPath: os.Path,
      apiDir: os.Path,
      orcaVersion: String
  ): String =
    val opening =
      s"""Create the Orca flow at $targetPath by copying $sourcePath and
         |applying these changes:""".stripMargin +
        "\n" + indentBlock(changes) + "\n\n" +
        """Keep the copied file's existing version-pinned header (`//> using
         |scala`/`//> using dep`/`//> using jvm`) and its line-1 `//`
         |one-line-description convention — update the description line only if
         |the fork's behavior changes enough to make the original one wrong.""".stripMargin
    changePrompt(opening, targetPath, apiDir, orcaVersion)

  /** The authoring task for edit-by-agent (ADR 0021 §6/§9 amendment):
    * `targetPath` is a sandbox copy of `sourcePath` (the flow's own real path)
    * that gets copied back OVER the original on success — worded as an edit,
    * not a fork, since the user picked "Edit a flow"; [[forkPrompt]]'s "create
    * by copying" framing would misdescribe what they asked for even though the
    * underlying mechanics (copy, then apply changes) are the same. The rest —
    * API pointers, compile-check, caveat — is [[changePrompt]]'s shared tail.
    */
  def editPrompt(
      changes: String,
      sourcePath: os.Path,
      targetPath: os.Path,
      apiDir: os.Path,
      orcaVersion: String
  ): String =
    val opening =
      s"""Edit the Orca flow: apply these changes to $targetPath, which is a
         |copy of $sourcePath:""".stripMargin +
        "\n" + indentBlock(changes) + "\n\n" +
        """Keep the file's existing version-pinned header (`//> using
         |scala`/`//> using dep`/`//> using jvm`) and its line-1 `//`
         |one-line-description convention — update the description line only if
         |these changes make the original one wrong.""".stripMargin
    changePrompt(opening, targetPath, apiDir, orcaVersion)

  /** A hand-authored flow's starting point (ADR 0021 §9 amendment,
    * Create+hand): [[versionPinLines]] under a placeholder line-1 description
    * (the [[initialPrompt]] convention the shell's flow listing reads), the
    * bare `import orca.{*, given}`, and a `flow(OrcaArgs(args)):` body with a
    * TODO comment plus `()` — the minimum that compiles (a comment alone is not
    * a statement, so the indented block needs a real expression too), left for
    * the user to fill in.
    */
  def skeletonFlow(orcaVersion: String): String =
    s"""// TODO: describe what this flow does
       |${versionPinLines(orcaVersion)}
       |
       |import orca.{*, given}
       |
       |flow(OrcaArgs(args)):
       |  // TODO: implement the flow
       |  ()
       |""".stripMargin
