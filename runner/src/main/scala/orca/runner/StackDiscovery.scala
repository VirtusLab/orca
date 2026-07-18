package orca.runner

import orca.{InStage, StackSettings}
import orca.agents.{Agent, Announce, JsonData, given}
import orca.events.OrcaEvent
import orca.settings.{SettingsEntry, StackKey}
import orca.subprocess.PathProbe
import orca.util.{PromptResource, TextUtil}

/** One command the discovery agent proposes for a task, with the repo-relative
  * file that justifies it and an optional free-text note (key/line/why) — the
  * evidence that makes the written settings file reviewable (ADR 0019).
  */
private[runner] case class DiscoveredCommand(
    command: String,
    evidencePath: String,
    evidenceNote: Option[String] = None
) derives JsonData

/** A task's proposed commands, or a one-line reason it was left unset. The
  * strict output schema requires BOTH keys on every task (Options nullable —
  * see [[orca.util.JsonSchemaGen]]), so the agent emits `"commands": []` /
  * `"unsetReason": null` for whichever side doesn't apply. The Scala-side
  * defaults keep the jsoniter parse lenient about a genuinely omitted field.
  */
private[runner] case class DiscoveredTask(
    commands: List[DiscoveredCommand] = Nil,
    unsetReason: Option[String] = None
) derives JsonData

/** The discovery result proper — the private evidence-carrying sibling of
  * [[orca.StackSettings]] (ADR 0019). Sent over the wire inside
  * [[StackDiscoveryReply]].
  */
private[runner] case class StackDiscoveryResult(
    format: DiscoveredTask,
    lint: DiscoveredTask,
    test: DiscoveredTask
) derives JsonData

/** Single-property envelope around [[StackDiscoveryResult]] — the actual
  * `resultAs` payload. Claude's `--json-schema` mode surfaces the schema's
  * top-level properties as the StructuredOutput tool's parameters, and the
  * cheap tier (haiku) reliably misbehaves with several — stuffing the whole
  * result under the FIRST parameter. A single-property root avoids that; it
  * matches every other cheap-tier payload in production
  * (`SelectedReviewers.names`, `ReviewResult.issues`).
  */
private[runner] case class StackDiscoveryReply(result: StackDiscoveryResult)
    derives JsonData

private[runner] object StackDiscoveryReply:
  /** Silent — discovery narrates each written line as its own `Step`, so an
    * auto-announced payload would compete.
    */
  given Announce[StackDiscoveryReply] = Announce.from(_ => "")

/** Agent-based stack discovery (ADR 0019 § Auto-discovery): one read-only
  * cheap-tier agent run proposes per-task commands with evidence; two orca-side
  * mechanical checks demote unresolvable ones to commented-out lines; the
  * assembled entries render into `.orca/settings.properties`.
  */
private[runner] object StackDiscovery:

  /** The principle-based discovery prompt (no real stack→command examples, one
    * fictional-stack example only — ADR 0019).
    */
  private[runner] val Prompt: String =
    PromptResource.load("/orca/runner/prompts/stack-discovery.md")

  /** Run discovery end to end: announce it, ask `agent`'s cheap tier (read-only
    * — the agent inspects the repo with its own tools; nothing is inlined into
    * the prompt) for a [[StackDiscoveryResult]], apply the two mechanical
    * checks against `workDir`, and narrate every resulting command/demotion
    * line — plus a warning per task left with no commands — as `Step` events.
    * Returns the surviving settings and the full entry list for the caller to
    * render and write.
    *
    * Deliberately catch-free (ADR 0019): a discovery failure propagates and
    * aborts the run as an ordinary surfaced setup failure, rather than being
    * degraded to writing an all-commented file — under the frozen-file
    * semantics that would turn a transient outage into a permanently recorded
    * "gates off".
    */
  def discover(
      agent: Agent[?],
      workDir: os.Path,
      emit: OrcaEvent => Unit
  )(using InStage): (StackSettings, List[SettingsEntry]) =
    emit(
      OrcaEvent.Step("no .orca/settings.properties — running stack discovery")
    )
    val result = agent.cheap.withReadOnly
      .resultAs[StackDiscoveryReply]
      .autonomous
      .run(Prompt, emitPrompt = false)
      .result
    val (entries, settings) = toEntries(
      result,
      unresolvedReason(_, workDir),
      evidenceExists(_, workDir)
    )
    narrateEntries(entries, emit)
    warnDisabledGates(settings, emit)
    (settings, entries)

  /** Narrate every command/demotion entry as its own `Step` event. Unset tasks
    * surface through [[warnDisabledGates]] instead.
    */
  private def narrateEntries(
      entries: List[SettingsEntry],
      emit: OrcaEvent => Unit
  ): Unit =
    entries.foreach:
      case SettingsEntry.Command(key, command, comment) =>
        emit(
          OrcaEvent.Step(
            s"  $key = ${collapse(command)}" +
              comment.fold("")(c => s"   # ${collapse(c)}")
          )
        )
      case SettingsEntry.Demoted(key, command, reason) =>
        emit(
          OrcaEvent.Step(
            s"  # $key = ${collapse(command)}   (${collapse(reason)})"
          )
        )
      case SettingsEntry.Unset(_, _) => ()

  /** Emit a warning `Step` for each task that ended up with no commands — its
    * gate is disabled until the settings file gains a live line.
    */
  private def warnDisabledGates(
      settings: StackSettings,
      emit: OrcaEvent => Unit
  ): Unit =
    List(
      StackKey.Format -> settings.format,
      StackKey.Lint -> settings.lint,
      StackKey.Test -> settings.test
    ).foreach: (key, commands) =>
      if commands.isEmpty then
        emit(
          OrcaEvent.Step(
            s"warning: stack settings: no ${key.raw} command — gate disabled"
          )
        )

  /** One-physical-line guard for `Step` messages, mirroring the render-side
    * sanitization.
    */
  private def collapse(s: String): String = TextUtil.collapseWhitespace(s)

  /** Matches a leading `NAME=value` environment-assignment token, so `FOO=bar
    * cargo check` resolves `cargo`, not `FOO=bar`.
    */
  private val AssignmentToken = "^[A-Za-z_][A-Za-z0-9_]*=".r

  /** First mechanical check (ADR 0019): `None` = resolvable, `Some(reason)` =
    * demote. Strips leading `VAR=` assignment tokens, then resolves the first
    * remaining word via [[PathProbe]] (builtins included, the same environment
    * stage-time `bash -c` inherits). cwd is `workDir`, so a repo-relative path
    * like `./script.sh` resolves as it will at stage time.
    */
  private[runner] def unresolvedReason(
      command: String,
      workDir: os.Path
  ): Option[String] =
    // Naive whitespace tokenization: a quoted token containing spaces (e.g.
    // `FOO="a b" cargo check`) splits mid-quote and demotes. Safe direction — a
    // visible demoted line in the settings file, hand-fixable.
    val words = command.trim.split("\\s+").toList.filter(_.nonEmpty)
    words.dropWhile(w => AssignmentToken.findPrefixOf(w).isDefined) match
      case Nil => Some("empty command")
      case word :: _ =>
        if PathProbe.resolves(word, workDir) then None
        else Some(s"$word: not found on PATH")

  /** Second mechanical check (ADR 0019): the cited evidence file must exist
    * inside the repo. Absolute paths and `..` traversal are rejected outright
    * (`os.SubPath` refuses both), so an agent-proposed citation can only ever
    * probe inside `workDir`.
    */
  private[runner] def evidenceExists(path: String, workDir: os.Path): Boolean =
    try os.exists(workDir / os.SubPath(path))
    catch case _: IllegalArgumentException => false

  /** Assemble the agent's `result` into settings-file entries plus the
    * [[StackSettings]] the run uses. The two mechanical checks are injected as
    * functions so this stays pure and process-free.
    *
    * Per command: passing both checks → a [[SettingsEntry.Command]] carrying
    * its evidence as the comment, and the command joins the returned settings;
    * failing one → a [[SettingsEntry.Demoted]] with the reason. A task that
    * proposed no commands becomes [[SettingsEntry.Unset]]; a task whose every
    * command was demoted is documented by the demoted lines themselves.
    */
  def toEntries(
      result: StackDiscoveryResult,
      // Some = the demotion reason — a bare String, human-facing text for the
      // demoted line, never branched on.
      unresolvedReason: String => Option[String],
      evidenceExists: String => Boolean
  ): (List[SettingsEntry], StackSettings) =
    def checkedEntry(key: String, cmd: DiscoveredCommand): SettingsEntry =
      unresolvedReason(cmd.command)
        .orElse(
          // A blank citation is checked before existence: `os.SubPath("")`
          // resolves to the repo root, which exists, so the existence check
          // would pass vacuously.
          Option.when(cmd.evidencePath.isBlank)("no evidence file cited")
        )
        .orElse(
          Option.when(!evidenceExists(cmd.evidencePath))(
            s"evidence file ${cmd.evidencePath} not found"
          )
        ) match
        case Some(reason) => SettingsEntry.Demoted(key, cmd.command, reason)
        case None =>
          SettingsEntry.Command(
            key,
            // Same collapse the renderer applies to every command line, so the
            // command the first run executes is identical to the written line.
            TextUtil.collapseNewlines(cmd.command),
            Some(cmd.evidencePath + cmd.evidenceNote.fold("")("; " + _))
          )

    def taskEntries(key: String, task: DiscoveredTask): List[SettingsEntry] =
      if task.commands.isEmpty then
        List(
          SettingsEntry
            .Unset(key, task.unsetReason.getOrElse("no evidence found"))
        )
      else task.commands.map(checkedEntry(key, _))

    val entries =
      taskEntries(StackKey.Format.raw, result.format) ++
        taskEntries(StackKey.Lint.raw, result.lint) ++
        taskEntries(StackKey.Test.raw, result.test)

    def surviving(key: StackKey): List[String] =
      entries.collect:
        case SettingsEntry.Command(k, command, _) if k == key.raw => command

    val settings = StackSettings(
      format = surviving(StackKey.Format),
      lint = surviving(StackKey.Lint),
      test = surviving(StackKey.Test)
    )
    (entries, settings)
