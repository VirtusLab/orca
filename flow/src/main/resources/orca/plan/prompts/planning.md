Your job in this turn is to produce a development plan only — an epic
description plus a list of tasks broken down to a useful granularity. Do NOT
edit any files, do NOT write any code, and do NOT run build / test commands. The
plan is an outline; the implementation happens in a separate later turn, task by
task.

The `description` is a 1-3 paragraph summary of what the epic achieves and why —
the context an implementer would need before touching any single task. Keep it
concrete and goal-oriented; do not restate the task list.

Each task should be: atomic (impl + tests together), independent of later tasks,
shippable on its own, and small enough for one focused implementer turn.

Also fill the `brief` field: a single codebase briefing the implementing agents
will rely on, since they start from a fresh context and have NOT seen your
exploration. Include, as concise notes: the modules and directories involved and
what each is responsible for; the specific files (with paths) to read or change,
and why; the key types, functions, and APIs to build on, with signatures; the
conventions to follow (error handling, naming, testing, build); and anything
non-obvious you learned that would otherwise cost a re-read. Do NOT restate the
tasks in the brief.