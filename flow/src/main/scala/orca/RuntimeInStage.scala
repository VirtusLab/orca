package orca

/** The runtime's ONE named door to a forged [[InStage]] token. Every privileged
  * mint (stage bookkeeping, session recording, lifecycle setup/teardown) calls
  * [[token]], so auditing "who can mutate outside a stage?" is a single grep
  * for `RuntimeInStage` — the call sites ARE the whitelist. Library code must
  * never call `InStage.unsafe` directly.
  *
  * Test code is the sanctioned second caller of `InStage.unsafe`: tests mint
  * directly rather than going through this funnel, since they are exercising
  * stage-bound code in isolation rather than acting as the runtime.
  */
private[orca] object RuntimeInStage:
  def token(): InStage = InStage.unsafe
