package orca.settings

import orca.StackSettings

/** The closed set of settings-file keys; `raw` is the exact on-disk spelling
  * (keys are case-sensitive). Split into [[StackKey]] and [[AgentKey]] so the
  * stack-key and agent-key handling each match exhaustively over their own
  * cases — a key added without matching code fails to compile rather than
  * silently falling through.
  */
private[settings] sealed trait SettingKey:
  def raw: String

/** The stack-command keys: project-only, values append in file order. */
private[orca] enum StackKey(val raw: String) extends SettingKey:
  case Format extends StackKey("format")
  case Lint extends StackKey("lint")
  case Test extends StackKey("test")

  def commandsIn(settings: StackSettings): List[String] = this match
    case Format => settings.format
    case Lint   => settings.lint
    case Test   => settings.test

  def appendTo(settings: StackSettings, command: StackCommand): StackSettings =
    this match
      case Format => settings.copy(format = settings.format :+ command.value)
      case Lint   => settings.copy(lint = settings.lint :+ command.value)
      case Test   => settings.copy(test = settings.test :+ command.value)

private[orca] object StackKey:
  def tabulate(commands: StackKey => List[String]): StackSettings =
    StackSettings(
      format = commands(Format),
      lint = commands(Lint),
      test = commands(Test)
    )

/** The agent role keys: valid in both scopes, single-valued. */
private[orca] enum AgentKey(val raw: String) extends SettingKey:
  case PlanningAgent extends AgentKey("planningAgent")
  case CodingAgent extends AgentKey("codingAgent")
  case ReviewAgent extends AgentKey("reviewAgent")

private[settings] object SettingKey:
  val values: Array[SettingKey] = StackKey.values ++ AgentKey.values
  def fromRaw(s: String): Option[SettingKey] = values.find(_.raw == s)
