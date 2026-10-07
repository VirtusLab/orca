package orca.review

/** Where `reviewAndFixLoop` gets the change set under review. */
enum ReviewDiff:
  /** Everything the enclosing stage has produced since it began (ADR 0018
    * §2.1), re-sampled every round so each round's reviewers see the fixer's
    * edits whether or not it committed them.
    */
  case SampleFromStage

  /** Everything the run has produced since it started — since the commit HEAD
    * pointed at when the run bound its branch, recorded in the progress log —
    * re-sampled every round like [[SampleFromStage]]. For a review over the
    * whole branch, where a stage-scoped change set would miss what earlier
    * stages committed.
    *
    * A run whose progress log records no usable commit has no base to diff
    * against: `reviewAndFixLoop` then says so and returns without reviewing.
    */
  case WholeRun

  /** A caller-pinned diff, sent as given. Pinning also changes what reviewers
    * are told: the prompt does not claim the change set covers the stage, no
    * base commit is named (the pinned set need not be the stage's), the
    * selector's changed-file list is scraped from the diff text, and every
    * later round finds the same text, so a resumed reviewer is told there is no
    * new change set.
    */
  case Pinned(diff: String)
