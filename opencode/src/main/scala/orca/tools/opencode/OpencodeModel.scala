package orca.tools.opencode

import orca.OrcaFlowException
import orca.agents.Model

/** Construction and parsing of OpenCode's `provider/model` identifiers.
  *
  * OpenCode addresses every model as `<providerID>/<modelID>` (e.g.
  * `anthropic/claude-opus-4-8`, `openai/gpt-5.4`, `ollama/llama3.1`). The wire
  * layer (`OpencodeArgs`) needs the two halves separately, so this is the one
  * place that joins and splits them — callers never hand-concatenate.
  */
object OpencodeModel:

  /** Build a provider-qualified [[Model]], e.g. `OpencodeModel("ollama",
    * "llama3.1")`. Both parts must be non-empty; an empty part is a caller
    * defect, not a recoverable condition.
    */
  def apply(providerID: String, modelID: String): Model =
    require(providerID.nonEmpty, "providerID must be non-empty")
    require(modelID.nonEmpty, "modelID must be non-empty")
    Model(s"$providerID/$modelID")

  /** Split a `provider/model` id into `(providerID, modelID)` on the **first**
    * `/` only — a model id may itself contain slashes (e.g.
    * `lmstudio/google/gemma-3n-e4b` → `("lmstudio", "google/gemma-3n-e4b")`).
    * Throws on a value that isn't a non-empty `provider/model`.
    */
  private[opencode] def split(model: Model): (String, String) =
    Model.name(model).split("/", 2) match
      case Array(provider, rest) if provider.nonEmpty && rest.nonEmpty =>
        (provider, rest)
      case _ =>
        throw OrcaFlowException(
          s"not a provider/model id: ${Model.name(model)}"
        )
