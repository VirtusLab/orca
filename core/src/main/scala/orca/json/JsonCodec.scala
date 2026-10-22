package orca.json

import language.experimental.captureChecking

import caps.assumeSafe
import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter,
  readFromArray,
  readFromString,
  writeToArray,
  writeToString
}
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  ConfiguredJsonValueCodec,
  JsonCodecMaker
}

/** A jsoniter-scala codec that safe code can derive: `derives JsonCodec`, or
  * `JsonCodec.derived(using JsonCodec.config.with…)` for a non-default config.
  */
@assumeSafe
trait JsonCodec[A] extends ConfiguredJsonValueCodec[A]

@assumeSafe
object JsonCodec:

  /** jsoniter's default config, to refine with its `with…` methods. */
  inline def config: CodecMakerConfig = CodecMakerConfig

  inline def derived[A](using
      inline config: CodecMakerConfig = CodecMakerConfig
  ): JsonCodec[A] =
    wrap(JsonCodecMaker.make[A](config))

  /** `JsonCodecMaker.make`: a plain codec, e.g. for a collection type. */
  inline def make[A](using
      inline config: CodecMakerConfig = CodecMakerConfig
  ): JsonValueCodec[A] =
    JsonCodecMaker.make[A](config)

  /** Throws `JsonReaderException` when `s` is not an `A`. */
  def readString[A](s: String)(using JsonValueCodec[A]): A =
    readFromString[A](s)
  def writeString[A](a: A)(using JsonValueCodec[A]): String = writeToString(a)

  /** Throws `JsonReaderException` when `bytes` are not an `A`. */
  def readBytes[A](bytes: IArray[Byte])(using JsonValueCodec[A]): A =
    readFromArray[A](IArray.genericWrapArray(bytes).toArray)
  def writeBytes[A](a: A)(using JsonValueCodec[A]): IArray[Byte] =
    // Nothing else holds the array, so the immutable view captures nothing.
    caps.unsafe.unsafeAssumePure(IArray.unsafeFromArray(writeToArray(a)))

  /** `codec` as a [[JsonCodec]]. */
  private[json] def wrap[A](codec: JsonValueCodec[A]): JsonCodec[A] =
    new JsonCodec[A]:
      def decodeValue(in: JsonReader, default: A): A =
        codec.decodeValue(in, default)
      def encodeValue(x: A, out: JsonWriter): Unit = codec.encodeValue(x, out)
      def nullValue: A = codec.nullValue
