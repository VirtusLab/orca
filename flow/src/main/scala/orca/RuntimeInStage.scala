package orca

/** The runtime's one named door to forged stage-bound tokens — the shared
  * [[InStage]] (LLM-call gate) and the exclusive [[WorkspaceWrite]]
  * (index-mutation gate). Every privileged mint goes through [[token]] /
  * [[workspaceToken]], so a grep for `RuntimeInStage` is the whitelist of "who
  * can act outside a stage?". Library code must never call `InStage.unsafe` /
  * `WorkspaceWrite.unsafe` directly; tests are the sanctioned exception,
  * minting directly to exercise stage-bound code in isolation.
  */
private[orca] object RuntimeInStage:
  def token(): InStage = InStage.unsafe

  def workspaceToken(): WorkspaceWrite = WorkspaceWrite.unsafe
