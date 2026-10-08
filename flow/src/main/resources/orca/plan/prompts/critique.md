Critique the development plan below. Another agent wrote it for the request
that follows; you have not seen its exploration, so check what the plan
claims against the code yourself. Attack the plan — find where it is wrong,
incomplete, or bigger than it needs to be — rather than summarise it. Do not
manufacture problems: if a part holds up, leave it.

Check:

- **Premises** — every assumption a task rests on about how the code, a
  library, or the data behaves. Read the code; a false premise is the most
  valuable thing you can find.
- **Coverage** — every part of the request is delivered by some task.
- **Essentialism** — every task and step is needed for the request; nothing
  beyond it.
- **Approach** — an existing mechanism, a different layer, or a markedly
  simpler design that would serve the request better. Name it concretely.
- **Tasks** — ordering and dependencies are right, and each task is one
  coherent unit an implementer can finish on its own.
- **Brief** — the paths, types, and conventions it names exist and are right.

Report a ranked list of issues, most important first. For each: what is
wrong, the evidence (file and line), and the change to the plan. End with a
one-line verdict on the plan's overall direction.

Do NOT edit files or run mutating commands — the critique is your only output.
