package orca.pr

import orca.progress.ProgressStore

/** Build a stable, per-run HTML comment marker for use with
  * [[orca.tools.GitHubTool.upsertComment]]. Invisible in GitHub's rendered UI
  * but detectable in the raw body, so a re-run finds and updates its own prior
  * comment instead of duplicating it.
  *
  * `userPrompt` is hashed so runs for different prompts produce distinct
  * markers even with the same `purpose`; `purpose` further namespaces the
  * marker within a run (e.g. `"reject"`, `"triage"`). Produces e.g. `<!--
  * orca:a1b2c3d4e5f6:reject -->`.
  */
def orcaCommentMarker(userPrompt: String, purpose: String): String =
  s"<!-- orca:${ProgressStore.hashPrompt(userPrompt)}:$purpose -->"
