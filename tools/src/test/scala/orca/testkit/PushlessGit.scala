package orca.testkit

import orca.WorkspaceWrite
import orca.tools.{GitTool, NoDefaultBase, PushFailure}

/** The real git with the calls that need a remote stubbed — `push` and
  * `defaultBase` answer `pushAnswer` / `base`, `diffVsBase` the fixed
  * `branchDiff` — so a test driving the PR helpers needs no remote and its
  * summariser sees a small diff. Everything else is `underlying`, so branch
  * and commit work is real.
  *
  * `pushAnswer` and `base` are by-name: a test pins a refusal with a `Left`,
  * and the failures git throws rather than models (no permission, no network)
  * by throwing from the expression itself.
  */
class PushlessGit(
    underlying: GitTool,
    branchDiff: String = "stub-diff",
    pushAnswer: => Either[PushFailure, Unit] = Right(()),
    base: => Either[NoDefaultBase, String] = Right("main")
) extends GitTool:
  export underlying.{push => _, defaultBase => _, diffVsBase => _, *}

  def push()(using WorkspaceWrite): Either[PushFailure, Unit] = pushAnswer
  def defaultBase(): Either[NoDefaultBase, String] = base
  def diffVsBase(base: String): String = branchDiff
