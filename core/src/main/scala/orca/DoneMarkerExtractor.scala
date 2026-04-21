package orca

object DoneMarkerExtractor:

  private val Marker: String = DefaultPromptTemplate.DoneMarker

  /** Given raw interactive-session output, return the text that follows the
    * final `<<<ORCA_DONE>>>` marker (trimmed). Throws OrcaFlowException when
    * the marker is missing — the caller is expected to surface this so the
    * session can be retried.
    */
  def extract(raw: String): String =
    raw.lastIndexOf(Marker) match
      case -1 =>
        throw OrcaFlowException(
          s"Expected marker '$Marker' in agent output, but it was missing"
        )
      case i => raw.substring(i + Marker.length).trim
