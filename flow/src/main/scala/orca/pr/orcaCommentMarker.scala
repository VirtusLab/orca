package orca.pr

import orca.progress.ProgressStore

/** Build a stable, per-run HTML comment marker for use with
  * [[orca.tools.GitHubTool.upsertComment]]. The marker is an HTML comment
  * invisible in the rendered GitHub UI but detectable in the raw body, enabling
  * a re-run to find and update its own prior comment instead of duplicating it.
  *
  * `userPrompt` is hashed so two different flow runs for different prompts
  * produce distinct markers even with the same `purpose`. `purpose` further
  * namespaces the marker within a single run (e.g. `"reject"`, `"triage"`).
  *
  * Example: `orcaCommentMarker(userPrompt, "reject")` produces a single-line
  * marker: `<!-- orca:a1b2c3d4e5f6:reject -->`
  */
def orcaCommentMarker(userPrompt: String, purpose: String): String =
  s"<!-- orca:${ProgressStore.hashPrompt(userPrompt)}:$purpose -->"
