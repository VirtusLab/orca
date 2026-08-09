package orca.agents

/** How a backend's wire delivers the payload of a structured (`resultAs[O]`)
  * turn. Declared per backend via `AgentBackend.structuredOutputMode`; read by
  * [[Prompts]], so every delivery instruction matches the wire, and by the
  * autonomous drain (`orca.backend.Conversations`), to know whether the closing
  * assistant turn is the payload.
  */
enum StructuredOutputMode:
  /** The CLI injects a `StructuredOutput` tool and the model answers by calling
    * it; the payload never arrives as reply text, though the turn may still
    * write ordinary prose. The schema rides on that tool definition on every
    * turn, so the prompt neither repeats it nor asks for JSON — it only names
    * the tool. Claude (`--json-schema`), opencode (`format: json_schema`).
    */
  case Tool

  /** The final reply text IS the JSON value. Whatever the CLI constrains on
    * top, the prompt is the only contract that holds on every turn, so it ships
    * the schema and the raw-JSON rules. Codex, gemini, pi.
    */
  case RawText
