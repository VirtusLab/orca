package orca.testkit

import orca.WorkspaceWrite
import orca.tools.{GitTool, PushFailure}

/** The real git with the two calls that need a remote stubbed — `push` succeeds
  * without one and `defaultBase` answers `main` instead of resolving
  * `origin/HEAD` — and `diffVsBase` answering the fixed `branchDiff`, so a test
  * driving the PR helpers needs no remote and its summariser sees a small diff.
  * Everything else is `underlying`, so branch and commit work is real.
  */
class PushlessGit(underlying: GitTool, branchDiff: String = "stub-diff")
    extends GitTool:
  export underlying.{push => _, defaultBase => _, diffVsBase => _, *}

  def push()(using WorkspaceWrite): Either[PushFailure, Unit] = Right(())
  def defaultBase(): String = "main"
  def diffVsBase(base: String): String = branchDiff
