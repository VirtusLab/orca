package orca.shell

import orca.util.PromptResource

/** The orca build this shell is, and so the orca every flow it launches runs
  * on: the pin written into built-in and authored flows, and the `--dep` forced
  * over a flow's own pin.
  */
private[shell] enum OrcaBuild:
  /** A tagged release, resolvable from Maven Central. */
  case Release(version: String)

  /** A dynver build between tags (`+`-suffixed). Published only to the local
    * Ivy repository, by `sbt publishLocal`.
    */
  case Snapshot(version: String)

  def version: String

  /** The `//> using` lines that make a flow script resolve this build. */
  def usingDirectives: String = this match
    case Release(v) => s"""//> using dep "${OrcaBuild.Module}:$v""""
    case Snapshot(v) =>
      s"""//> using dep "${OrcaBuild.Module}:$v"
         |//> using repository ivy2Local""".stripMargin

  /** `scala-cli` options that run a flow on this build, replacing the flow's
    * own orca pin.
    */
  def forceArgs: Seq[String] = this match
    case Release(v) => Seq("--dep", s"${OrcaBuild.Module}:$v")
    case Snapshot(v) =>
      Seq("--dep", s"${OrcaBuild.Module}:$v", "--repository", "ivy2Local")

  /** The git ref of this build's sources on GitHub; a snapshot's commit may not
    * be pushed, so it points at `master`.
    */
  def gitRef: String = this match
    case Release(v)  => s"v$v"
    case Snapshot(_) => "master"

private[shell] object OrcaBuild:
  /** The coordinate of orca's user-facing library, as scala-cli spells it. */
  val Module = "org.virtuslab::orca"

  def of(version: String): OrcaBuild =
    if version.contains("+") then Snapshot(version) else Release(version)

  /** This shell's build, from the version resource `build.sbt` generates. */
  lazy val current: OrcaBuild =
    of(PromptResource.load("/orca/shell/version").trim)
