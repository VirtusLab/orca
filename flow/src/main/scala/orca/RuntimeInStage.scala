package orca

/** The runtime's ONE named door to forged stage-bound tokens — the shared
  * [[InStage]] (LLM-call gate) and the exclusive [[WorkspaceWrite]]
  * (index-mutation gate). Every privileged mint (stage bookkeeping, session
  * recording, lifecycle setup/teardown) calls [[token]] or [[workspaceToken]],
  * so auditing "who can act outside a stage?" is a single grep for
  * `RuntimeInStage` — the call sites ARE the whitelist. Library code must never
  * call `InStage.unsafe` / `WorkspaceWrite.unsafe` directly.
  *
  * Test code is the sanctioned second caller of the `unsafe` mints: tests mint
  * directly rather than going through this funnel, since they are exercising
  * stage-bound code in isolation rather than acting as the runtime.
  */
private[orca] object RuntimeInStage:
  def token(): InStage = InStage.unsafe

  def workspaceToken(): WorkspaceWrite = WorkspaceWrite.unsafe
