The plan we just produced will be implemented by a separate coding agent,
starting from a fresh context — it has NOT seen your exploration of this
codebase. Write a single briefing it can rely on so it doesn't have to
rediscover it.

Include, as concise notes: the modules and directories involved and what each is
responsible for; the specific files (with paths) it will read or change, and
why; the key types, functions, and APIs it will build on, with signatures; the
conventions to follow (error handling, naming, testing, build); and anything
non-obvious you learned that would otherwise cost a re-read.

Do NOT restate the tasks or the plan — the agent already has those, and do NOT
edit files or run mutating commands. Output only the briefing, as plain
markdown.
