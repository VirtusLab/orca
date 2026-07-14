package orca

/** Three-state default for stack-dependent parameters: omission resolves from
  * the project's [[StackSettings]] (ADR 0019).
  */
enum Configured[+A]:
  /** Resolve from `ctx.stackSettings` (the default). */
  case FromSettings

  /** Explicitly disabled for this call, regardless of settings. */
  case Off

  /** Explicit value; settings ignored. */
  case Use(value: A)
