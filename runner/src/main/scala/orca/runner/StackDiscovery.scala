package orca.runner

import orca.StackSettings
import orca.agents.{Announce, JsonData, given}
import orca.settings.{SettingKey, SettingsEntry}
import orca.util.PromptResource

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
  * defaults let the agent omit whichever side doesn't apply.
  */
private[runner] case class DiscoveredTask(
    commands: List[DiscoveredCommand] = Nil,
    unsetReason: Option[String] = None
) derives JsonData

/** The discovery agent's structured reply — the private evidence-carrying
  * sibling of [[orca.StackSettings]] (ADR 0019).
  */
private[runner] case class StackDiscoveryResult(
    format: DiscoveredTask,
    lint: DiscoveredTask,
    test: DiscoveredTask
) derives JsonData

private[runner] object StackDiscoveryResult:
  /** Silent — discovery narrates each written line as its own `Step`, so an
    * auto-announced payload would compete.
    */
  given Announce[StackDiscoveryResult] = Announce.from(_ => "")

/** Agent-based stack discovery (ADR 0019 § Auto-discovery): one read-only
  * cheap-tier agent run proposes per-task commands with evidence; two orca-side
  * mechanical checks demote unresolvable ones to commented-out lines; the
  * assembled entries render into `.orca/settings.properties`.
  */
private[runner] object StackDiscovery:

  /** The principle-based discovery prompt (no real stack→command examples — ADR
    * 0019 records the rejection; one fictional-stack example only).
    */
  private[runner] val Prompt: String =
    PromptResource.load("/orca/runner/prompts/stack-discovery.md")

  /** Assemble the agent's `result` into settings-file entries plus the
    * [[StackSettings]] the run uses, applying the two mechanical checks —
    * injected as functions so this stays pure and process-free:
    * `commandUnresolvable` returns the demotion reason for a command whose
    * first word doesn't resolve, `evidenceExists` answers whether a cited
    * evidence file is present.
    *
    * Per command: passing both checks → a [[SettingsEntry.Command]] carrying
    * its evidence as the comment, and the command joins the returned settings;
    * failing one → a [[SettingsEntry.Demoted]] with the failure reason. A task
    * that proposed no commands at all becomes [[SettingsEntry.Unset]] with the
    * agent's reason (or a stock one); a task whose every command was demoted is
    * documented by the demoted lines themselves — no contradictory "no evidence
    * found" line is added.
    */
  def toEntries(
      result: StackDiscoveryResult,
      commandUnresolvable: String => Option[String],
      evidenceExists: String => Boolean
  ): (List[SettingsEntry], StackSettings) =
    def checkedEntry(key: String, cmd: DiscoveredCommand): SettingsEntry =
      commandUnresolvable(cmd.command)
        .orElse(
          Option.when(!evidenceExists(cmd.evidencePath))(
            s"evidence file ${cmd.evidencePath} not found"
          )
        ) match
        case Some(reason) => SettingsEntry.Demoted(key, cmd.command, reason)
        case None =>
          SettingsEntry.Command(
            key,
            cmd.command,
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
      taskEntries(SettingKey.Format.raw, result.format) ++
        taskEntries(SettingKey.Lint.raw, result.lint) ++
        taskEntries(SettingKey.Test.raw, result.test)

    def surviving(key: SettingKey): List[String] =
      entries.collect:
        case SettingsEntry.Command(k, command, _) if k == key.raw => command

    val settings = StackSettings(
      format = surviving(SettingKey.Format),
      lint = surviving(SettingKey.Lint),
      test = surviving(SettingKey.Test)
    )
    (entries, settings)
