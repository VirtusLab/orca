package orca.shell

/** The shell's running version, from the jar manifest (`Implementation-Version`)
  * — the same `OrcaBanner.version` pattern `runner` uses, since all modules
  * release under one dynver version. `"dev"` when running from class
  * directories (local `sbt run` or the test suite), where there's no manifest.
  */
object ShellVersion:

  def value: String =
    Option(getClass.getPackage.getImplementationVersion).getOrElse("dev")

  /** True for a plain release tag (dynver, e.g. `"0.0.18"`); false for a
    * dynver snapshot (carries a `+<commits>-<sha>` suffix) or `"dev"`.
    */
  def isRelease(v: String): Boolean = v != "dev" && !v.contains("+")

  def isRelease: Boolean = isRelease(value)
