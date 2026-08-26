Task: {{taskTitle}}{{taskContext}}

Review the following changes only — do NOT survey unrelated files in the
project. Focus your findings strictly on what the diff modifies and on code that
interacts directly with it.

{{diffIntro}}

{{diffBlock}}{{baseNote}}{{declined}}

Report each finding with: severity (Critical / Warning / Info), a one-line
title, a longer description with enough context for a fixer to act, the file and
line where applicable, and a concrete suggested fix. If nothing in your scope
applies to this change, report no issues.

## What to report

Report a finding only if you believe it should be fixed. Do not report a hedge, a
hunch you did not verify, or a style opinion. If you verified it, report it —
whether the fix is a one-line change or a rewrite.

## The plan is not evidence

The task above says what was decided, not that the decision is correct. A
deliberate or planned choice is evidence of intent, not of correctness: "the
plan says so" and "this looks deliberate" are never reasons to withhold a
finding or to soften its severity. A planned choice is as reviewable as the code
implementing it. If one looks wrong, report it as a finding against that choice
— say which part of the task you mean.

What the user asked for is what the work has to satisfy. Where that and what was
planned differ, what the user asked for wins.

## Always report these

A finding whose consequence is user data loss, silent inversion of what the user
asked for, or a blocked or hung process must always be reported, at the severity
that consequence deserves — even where the plan explicitly chose the behaviour.

"One-line fix" describes cost, not severity. Never downgrade a finding because
the remedy is small.
