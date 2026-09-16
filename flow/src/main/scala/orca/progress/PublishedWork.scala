package orca.progress

import orca.agents.JsonData

/** The fact that a run published its work somewhere durable, as the
  * human-readable `reference` that names it — a PR, MR, or change URL. Written
  * by the stage that published, and read back by the lifecycle to decide the
  * branch handoff and to name the destination in the closing summary.
  *
  * The reference is display-only — nothing dereferences it, so it need not be a
  * forge-specific handle.
  */
case class PublishedWork(reference: String) derives JsonData
