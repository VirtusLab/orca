package orca.testkit

import orca.WorkspaceWrite
import orca.tools.{GitTool, PushFailure}

/** The real git with the two calls that need a remote stubbed — `push` answers
  * `pushAnswer` without one and `defaultBase` answers `main` instead of
  * resolving `origin/HEAD` — and `diffVsBase` answering the fixed `branchDiff`,
  * so a test driving the PR helpers needs no remote and its summariser sees a
  * small diff. Everything else is `underlying`, so branch and commit work is
  * real.
  *
  * `pushAnswer` and `base` are by-name: a test pins a refused push with a
  * `Left`, and the failures git throws rather than models (no permission, no
  * network, no resolvable `origin/HEAD`) by throwing from the expression
  * itself.
  */
class PushlessGit(
    underlying: GitTool,
    branchDiff: String = "stub-diff",
    pushAnswer: => Either[PushFailure, Unit] = Right(()),
    base: => String = "main"
) extends GitTool:
  export underlying.{push => _, defaultBase => _, diffVsBase => _, *}

  def push()(using WorkspaceWrite): Either[PushFailure, Unit] = pushAnswer
  def defaultBase(): String = base
  def diffVsBase(base: String): String = branchDiff
