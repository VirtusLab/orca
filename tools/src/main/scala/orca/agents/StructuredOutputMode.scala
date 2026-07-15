package orca.agents

/** How a backend's wire delivers the payload of a structured (`resultAs[O]`)
  * turn — declared per backend via `AgentBackend.structuredOutputMode` and
  * consumed by [[Prompts.autonomous]], so the prompt's delivery instruction
  * names the mechanism the wire actually expects instead of contradicting it.
  */
enum StructuredOutputMode:
  /** The backend's CLI enforces the schema via an injected `StructuredOutput`
    * tool whose parameters are the schema's top-level properties — the result
    * arrives as that tool call, never as reply text (claude's `--json-schema`).
    */
  case Tool

  /** The final reply text IS the JSON value; orca (or the backend's own
    * constrained decoding, e.g. codex's `--output-schema`) treats the text as
    * the payload. Codex, gemini, opencode, pi.
    */
  case RawText
