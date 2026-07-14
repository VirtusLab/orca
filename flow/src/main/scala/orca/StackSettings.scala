package orca

/** Resolved per-project tooling commands (ADR 0019). Each command runs via
  * `bash -c`; an empty list means the task is disabled/unknown.
  */
case class StackSettings(
    format: List[String] = Nil,
    lint: List[String] = Nil,
    test: List[String] = Nil
)

object StackSettings:
  val empty: StackSettings = StackSettings()
