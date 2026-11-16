package orca.agents

import orca.OrcaFlowException
import orca.json.JsonSchemas
import sttp.tapir.Schema

/** JSON-Schema string for a type's `tapir.Schema`, in OpenAI's strict
  * structured-output dialect ([[JsonSchemas.strict]]); a type with no strict
  * form throws [[OrcaFlowException]], rather than emit a schema the backend
  * would reject later and more opaquely.
  *
  * The post-processing applies to every backend, which is why it lives at the
  * backend-agnostic `resultAs[O]` seam. The one schema string per `O` is both
  * passed to native schema flags (claude `--json-schema`, codex
  * `--output-schema`) and embedded into the prompt template all backends
  * receive (`Prompts`; pi/gemini/opencode have no native flag). Strict-mode
  * output is still valid JSON Schema, just more constrained, so the strictest
  * dialect any backend requires is safe as the single common form.
  */
private[orca] object JsonSchemaGen:
  def apply[O](using Schema[O]): String =
    JsonSchemas.strict[O].fold(m => throw OrcaFlowException(m), identity)
