package orca.runlog

import orca.json.JsonCodec

/** How an attempt ended, as [[RunEvent.AttemptFinished]] records it. */
private[orca] enum AttemptOutcome:
  case Succeeded, Failed

private[orca] object AttemptOutcome:
  given codec: JsonCodec[AttemptOutcome] =
    JsonCodec.derived[AttemptOutcome](using
      JsonCodec.config.withDiscriminatorFieldName(None)
    )
