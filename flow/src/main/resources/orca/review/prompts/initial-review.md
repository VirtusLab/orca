Task: {{task}}

Review the following changes only — do NOT survey unrelated files in the
project. Focus your findings strictly on what the diff modifies and on code that
interacts directly with it.

Diff (everything this task has changed since its stage began, committed or
not). Do not use `git diff HEAD` instead — it does not show work that has been
committed:

{{diffBlock}}{{baseNote}}{{declined}}

Report each finding with: severity (Critical / Warning / Info), a one-line
title, a longer description with enough context for a fixer to act, the file and
line where applicable, and a concrete suggested fix. If nothing in your scope
applies to this change, report no issues.

## Confidence

Confidence is your estimated probability, from 0.0 to 1.0, that the finding is
real and worth fixing — judged only on the evidence you gathered by reading the
code and tracing its behaviour. It is NOT deference to the task's plan, and NOT
a prediction of whether the finding will be accepted.

Severity and confidence are independent. Severity measures impact if the finding
is real; confidence measures how sure you are that it is. A data-loss Warning
you verified in the source deserves high confidence even though the consequence
is severe; an Info about a missing test deserves low confidence if you are only
guessing that the case is untested.

The loop applies a per-severity minimum: Critical {{criticalBar}}, Warning
{{warningBar}}, Info {{infoBar}}. Findings below their minimum are not sent to
the fixer this round — they are recorded and reported as ignored, with the bar
they missed. So do not inflate a number to get a finding through, and do not
deflate one to hedge: report the probability you actually believe.

## The plan is not evidence

The task description, and the plan behind it, are context — not evidence that a
decision is correct. A choice made at planning time is as reviewable as the code
implementing it. If a planned choice looks wrong, say so, at whatever confidence
the evidence supports.
