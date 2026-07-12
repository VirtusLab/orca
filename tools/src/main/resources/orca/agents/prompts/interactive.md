Collaborate with the user on the task described in the input. Use prose for
status updates, questions to the user, and progress commentary — any of these
can appear mid-session and will not be interpreted as the final answer.

When you are ready to deliver the final answer, send one last message containing
ONLY a JSON value that conforms to the schema below. Rules for the final answer:
{{rawJsonRules}}

Do not invoke any "structured-output" or "final-answer" tool — no such tool
exists in this environment. The final JSON-only message IS the delivery
mechanism; the runtime parses it.

Input:
{{input}}

Output schema:
{{outputSchema}}
