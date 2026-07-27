package orca.shell.actions

import orca.runner.RoleAgents
import orca.settings.AgentSpec

/** The startup configuration summary (ADR 0021 §4/§8): two lines printed right
  * after the banner, and again after Re-configure, so the user sees what they'd
  * be reconfiguring without running a flow. [[agentsLine]] resolves each role
  * via the SAME precedence a flow run applies
  * ([[RoleAgents.projectOverGlobal]], also used by `RoleAgents.resolveOne`),
  * minus its live-agent default-model fallback — no agent is built just to
  * print this line, so an unset role always shows bare `claude` here, even
  * though a real run might additionally show that backend's own configured
  * default model.
  */
private[shell] object ConfigSummary:

  /** `agents: planning=X, coding=Y, review=Z`, `X`/`Y`/`Z` rendered
    * `harness[:model]` (`claude` when a role is unset anywhere), with a
    * project-file override winning over the global one per role. Either file
    * being malformed renders a one-line warning instead of crashing.
    */
  def agentsLine(globalSettingsPath: os.Path, workDir: os.Path): String =
    (
      ConfigAction.show(globalSettingsPath),
      ConfigAction.showProject(workDir)
    ) match
      case (Left(error), _) => s"agents: $error"
      case (_, Left(error)) => s"agents: $error"
      case (Right(global), Right(project)) =>
        val roles = List(
          "planning" -> RoleAgents.projectOverGlobal(
            project.planning,
            global.planning
          ),
          "coding" -> RoleAgents.projectOverGlobal(
            project.coding,
            global.coding
          ),
          "review" -> RoleAgents.projectOverGlobal(
            project.review,
            global.review
          )
        )
        "agents: " +
          roles.map((role, spec) => s"$role=${renderSpec(spec)}").mkString(", ")

  private def renderSpec(spec: Option[AgentSpec]): String =
    spec.fold("claude"): s =>
      AgentSpec.harnessNameFor(s.backend) + s.model.fold("")(":" + _)

  /** `stack: format=X, lint=Y, test=Z` — one entry per [[StackAction.status]]
    * key, `off` for an empty/disabled one; `stack: not discovered yet —
    * detected on the first flow run` when there's no settings file yet or it
    * carries no stack lines. A malformed settings file renders
    * [[StackAction.status]]'s own error as a one-line warning instead of
    * crashing.
    */
  def stackLine(workDir: os.Path): String =
    StackAction.status(workDir) match
      case Left(error) => s"stack: $error"
      case Right(StackStatus.NoSettings | StackStatus.NoStackLines) =>
        "stack: not discovered yet — detected on the first flow run"
      case Right(StackStatus.Present(stack, _)) =>
        val keys =
          List(
            "format" -> stack.format,
            "lint" -> stack.lint,
            "test" -> stack.test
          )
        "stack: " + keys
          .map((key, commands) =>
            s"$key=${if commands.isEmpty then "off" else commands.mkString("; ")}"
          )
          .mkString(", ")
