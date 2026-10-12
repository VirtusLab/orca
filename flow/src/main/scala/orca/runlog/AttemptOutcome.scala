package orca.runlog

import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  ConfiguredJsonValueCodec
}

/** How an attempt ended, as [[RunEvent.AttemptFinished]] records it. */
private[orca] enum AttemptOutcome:
  case Succeeded, Failed

private[orca] object AttemptOutcome:
  given codec: ConfiguredJsonValueCodec[AttemptOutcome] =
    ConfiguredJsonValueCodec.derived[AttemptOutcome](using
      CodecMakerConfig.withDiscriminatorFieldName(None)
    )
