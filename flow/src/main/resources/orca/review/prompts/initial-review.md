Task: {{taskTitle}}{{taskContext}}

Review the change below — do NOT survey unrelated files in the project. Start
from what the diff modifies, and follow it into the code that has to keep
working now that it has changed: unchanged code is in scope precisely when the
change alters what it can be handed.

{{diffIntro}}

{{diffBlock}}{{baseNote}}{{declined}}

Report each finding with: a one-line title, a longer description with enough
context for a fixer to act, the file and line where applicable, and a concrete
suggested fix. If nothing in your scope applies to this change, report no
issues.

## What to report

Report a finding only if you believe it should be fixed. Do not report a hedge, a
hunch you did not verify, or a style opinion. If you verified it, report it —
whether the fix is a one-line change or a rewrite.

## What the change relaxes

Name the assumptions this change relaxes — a value that could not be absent and
now can, a set that was bounded and now is not, an order or a uniqueness that
was guaranteed and now is not, a caller that ran alone and now does not. Then
read the code that still relies on each one. Code the diff never touches is a
finding against this change when the change is what breaks it.

This is not licence to survey: follow only the code that receives what the
change alters.

## The plan is not evidence

The task above says what was decided, not that the decision is correct. A
deliberate or planned choice is evidence of intent, not of correctness: "the
plan says so" and "this looks deliberate" are never reasons to withhold a
finding. A planned choice is as reviewable as the code implementing it. If one
looks wrong, report it as a finding against that choice — say which part of the
task you mean.

What the user asked for is what the work has to satisfy. Where that and what was
planned differ, what the user asked for wins.

## Always report these

A finding whose consequence is {{mandatoryCategories}} must always be reported
— even where the plan explicitly chose the behaviour.

"One-line fix" describes cost. Never withhold or soften a finding because the
remedy is small.
