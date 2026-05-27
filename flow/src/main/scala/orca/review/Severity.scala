package orca.review

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  JsonCodecMaker
}
import sttp.tapir.Schema

enum Severity:
  case Critical
  case Warning
  case Info

object Severity:
  // Severity keeps its own Schema + JsonValueCodec so the enum renders as a
  // plain JSON string ("Critical", "Warning", "Info") in prompts and output
  // rather than the default object-with-discriminator shape.
  given Schema[Severity] = Schema.derivedEnumeration.defaultStringBased
  given JsonValueCodec[Severity] =
    JsonCodecMaker.make(CodecMakerConfig.withDiscriminatorFieldName(None))
