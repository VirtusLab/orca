package orca

import ox.flow.Flow

/** The sanctioned fan-out for flow scripts: run `f` over `items` concurrently
  * and collect the results, without importing Ox directly.
  *
  * The capability rules inside `f` are the fork rules: a shared [[InStage]]
  * crosses in — so ephemeral agent turns (`agent.run`, `chat.run`,
  * `resultAs[O]...run`) work — while the durable, flow-thread-only operations
  * refuse: `stage(...)` / `agent.session(...)` minting and `session.run(...)`
  * on a `FlowSession` all throw an `OrcaFlowException` when called from a fork
  * (each guards its own owner thread at runtime). Mint durable sessions on the
  * flow thread; hand forks [[orca.agents.Chat]]s.
  */
object Par:
  /** Run `f` over every item, at most `parallelism` at a time; results are
    * returned in COMPLETION order, not input order (pair items into the result
    * when order matters). The first failing `f` fails the whole call after
    * interrupting the in-flight siblings.
    */
  def mapUnordered[A, R](parallelism: Int)(items: Seq[A])(f: A => R): List[R] =
    Flow.fromIterable(items).mapParUnordered(parallelism)(f).runToList()
