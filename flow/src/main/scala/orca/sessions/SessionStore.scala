package orca.sessions

/** The durable-session records of one run, in machine-local cache rather than
  * in the committed progress log: a projection of the run's event log (ADR
  * 0025), covering the sessions minted since the run's last success.
  *
  * A backend session id is a handle into the coding agent's own store on this
  * machine: it means nothing in another checkout or on another machine, so it
  * is not history the feature branch should carry. Living in `.orca/cache/`
  * also makes it survive everything that erases uncommitted work — the failure
  * teardown's `git reset --hard`, its `git clean -fd`, and the resume-time
  * stash of a dirty tree — which is what makes `agent.session(name, seed)`'s
  * reuse branch reachable at all: a stage that fails is exactly the stage a
  * resume re-runs, and re-running it must land back on the conversation the
  * first attempt started.
  *
  * Losing records is not a failure mode, only a cost: a run that finds no
  * record at a key mints a fresh session and primes it from the seed, the same
  * uniform fallback as a backend conversation the probe reports gone.
  *
  * Safe to call from any thread; [[records]] sees every earlier [[upsert]].
  */
trait SessionStore:
  /** Every record this run has minted since its last success, in mint order.
    */
  def records(): List[SessionRecord]

  /** Upsert `record` by its [[orca.agents.SessionKey]]: replaces an existing
    * record with that key, or appends if none exists. Last write wins.
    */
  def upsert(record: SessionRecord): Unit
