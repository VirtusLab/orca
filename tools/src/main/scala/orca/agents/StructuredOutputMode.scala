package orca.agents

/** How a backend's wire delivers the payload of a structured (`resultAs[O]`)
  * turn. Declared per backend via `AgentBackend.structuredOutputMode`; read by
  * [[Prompts.autonomous]], so the delivery instruction matches the wire, and by
  * the autonomous drain (`orca.backend.Conversations`), to know whether the
  * closing assistant turn is the payload.
  */
enum StructuredOutputMode:
  /** The result arrives as a CLI-injected `StructuredOutput` tool call whose
    * parameters are the schema's top-level properties, never as reply text
    * (claude's `--json-schema`).
    */
  case Tool

  /** The final reply text IS the JSON value; orca (or the backend's own
    * constrained decoding, e.g. codex's `--output-schema`) treats the text as
    * the payload. Codex, gemini, opencode, pi.
    */
  case RawText
