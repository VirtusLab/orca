# Comments carry development-plan labels and change history

**Aspect**: conciseness  **Severity**: low

## Problem

AGENTS.md forbids both: "Never cite development-plan labels (epic/finding numbers) in comments — plans are deleted after execution" and "Comments state present-tense facts … never change history." Violations in the review-loop tests and two milder ones in main code:

- `flow/src/test/scala/orca/review/FixLoopTest.scala:216-218`:
  ```scala
  // BB8: file and line used to be independent Options, so (None, Some(l))
  // silently dropped the line. Location makes that combination
  // unrepresentable; this pins the still-valid file-without-line case.
  ```
  `BB8` references a deleted plan; "used to be" is history.
- `flow/src/test/scala/orca/review/ReviewAndFixTest.scala:1504` — a plan-step label baked into a test name: `test("reviewer LLM runs are tagged with the cost role (12.7)")`.
- `flow/src/test/scala/orca/review/ReviewAndFixTest.scala:577-579`:
  ```scala
  // ... the finding surfaces once as unaccounted — where
  // before it landed both as the fixer's ignored entry and as unaccounted.
  ```
  "where before it landed …" is history narration.
- Milder, history-framed phrasing of present properties:
  - `flow/src/main/scala/orca/review/ReviewIssue.scala:10`: "so the two can no longer appear independently."
  - `flow/src/main/scala/orca/review/FixOutcome.scala:57`: "a paraphrase can no longer record one real finding twice."

(`Lint.scala:93`'s "no longer show it" is fine — it describes a later call's runtime scenario, not code history.)

## Proposed solution

Prose-only edits:

- FixLoopTest.scala:216-218 → present tense, no label:
  `// A line without a file is unrepresentable (Location pairs them); this pins the still-valid file-without-line case.`
- ReviewAndFixTest.scala:1504 → rename the test to `"reviewer LLM runs are tagged with the cost role"`.
- ReviewAndFixTest.scala:577-579 → drop the trailing clause: end the sentence at "… and the finding surfaces once, as unaccounted."
- ReviewIssue.scala:10 → "so the two cannot appear independently."
- FixOutcome.scala:57 → "so a paraphrase cannot record one real finding twice."

Tests: none — comment/name edits only (the test rename changes reported test names; no assertion changes). Must NOT change: any test logic or production behaviour.

## Verification

**Verdict: CONFIRMED.**

Checked all five cited violations verbatim (FixLoopTest.scala:216-218 "BB8" + "used to be"; ReviewAndFixTest.scala:1504 "(12.7)" and 577-579 "where before it landed…"; ReviewIssue.scala:10; FixOutcome.scala:57) against AGENTS.md's plan-label and no-history rules; the `Lint.scala:93` exemption is correct (runtime scenario, not history). Replacement texts are exact, present-tense, and behaviour-neutral; implementable verbatim.
