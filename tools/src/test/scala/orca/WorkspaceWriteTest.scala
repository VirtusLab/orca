package orca

import ox.supervised

class WorkspaceWriteTest extends munit.FunSuite:

  test("a write checked off the minting thread throws"):
    val token = WorkspaceWrite.unsafe
    // Ox runs a `supervised` body on a fresh thread.
    val thrown = intercept[OrcaFlowException]:
      supervised(token.check("git.commit"))
    assert(
      thrown.getMessage.startsWith("git.commit called off the stage's thread"),
      thrown.getMessage
    )

end WorkspaceWriteTest
