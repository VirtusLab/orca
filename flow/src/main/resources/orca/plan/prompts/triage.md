Triage this bug report and produce a structured verdict:

- `isBug`: is the report an actual defect, or intended behavior / user error
  / out-of-scope? If false, set `notBugExplanation` (a short reply we'll
  post on the issue) and leave the other fields at empty defaults.
- `canTest`: can a focused unit test reproduce this on CI? If yes, set
  `failingTestPath` (use the project's existing test framework and layout
  conventions) and pick a kebab-case `branchName`. Set `summary` to a
  one-line PR title.
- If `isBug` is true but `canTest` is false, fill in `reproductionSteps`
  — they'll be posted back on the issue.
