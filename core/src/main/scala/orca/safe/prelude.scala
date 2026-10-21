package orca.safe

import language.experimental.captureChecking

import caps.assumeSafe

import java.nio.file.FileSystems
import scala.language.implicitConversions

/** Pure helpers over libraries safe code can't reference directly. Import with
  * `import orca.safe.prelude.{*, given}`.
  */
@assumeSafe
object prelude:
  extension [A](a: A)
    // Not inline: Scala 3.10.0 types an inline argument's proxy without its
    // captures, so `.discard` on a capturing value fails capture checking.
    def discard: Unit = ()
    inline def pipe[B](inline f: A => B): B = f(a)
    inline def tap(inline f: A => Unit): A =
      f(a)
      a

  /** `ox.either`, without its compile-time check against nesting. */
  inline def either[E, A](
      inline body: scala.util.boundary.Label[Either[E, A]] ?=> A
  ): Either[E, A] =
    scala.util.boundary(Right(body))
  export ox.either.{catching, ok, orThrow}

  /** `scala.util.control.NonFatal`. */
  object NonFatal:
    def unapply(t: Throwable): Option[Throwable] =
      scala.util.control.NonFatal.unapply(t)

  export scala.concurrent.duration.DurationInt
  export java.nio.charset.StandardCharsets.UTF_8

  // Imported givens take precedence over os-lib's implicit-scope conversions,
  // which safe code can't reference.
  given Conversion[String, os.PathChunk] = os.PathChunk.StringPathChunk(_)
  given Conversion[os.RelPath, os.PathChunk] = os.PathChunk.RelPathChunk(_)
  given Conversion[os.SubPath, os.PathChunk] = os.PathChunk.SubPathChunk(_)
  val up: os.RelPath = os.up
  val sub: os.SubPath = os.sub

  /** `path` resolved against `base` unless absolute. */
  def resolvePath(path: String, base: os.Path): os.Path = os.Path(path, base)

  /** `path` as a relative path; throws unless it is one. */
  def relPath(path: String): os.RelPath = os.RelPath(path)

  /** `path` as a path below a root; throws unless it is one. */
  def subPath(path: String): os.SubPath = os.SubPath(path)

  /** `path` as an absolute path; throws unless it is one. */
  def absolutePath(path: String): os.Path = os.Path(path)

  /** `text` encoded as UTF-8. */
  def utf8Bytes(text: String): IArray[Byte] =
    // Nothing else holds the array, so the immutable view captures nothing.
    caps.unsafe.unsafeAssumePure(IArray.unsafeFromArray(text.getBytes(UTF_8)))

  /** `bytes` decoded as UTF-8, malformed input replaced. */
  def utf8String(bytes: IArray[Byte]): String =
    String(IArray.genericWrapArray(bytes).toArray, UTF_8)

  /** SHA-256 of `bytes`. */
  def sha256(bytes: Array[Byte]): Array[Byte] =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

  /** A random UUID's string form. Randomness is untracked, like
    * `scala.util.Random`, which safe code reaches directly.
    */
  def randomUuid(): String = java.util.UUID.randomUUID().toString

  /** The classpath resource at absolute `path` as UTF-8 text, `None` when there
    * is none. Resources are part of the program, not effects.
    */
  def resourceText(path: String): Option[String] =
    Option(getClass.getResourceAsStream(path)).map: stream =>
      try scala.io.Source.fromInputStream(stream, "UTF-8").mkString
      finally stream.close()

  /** A matcher for the JVM's default glob syntax. */
  def globMatcher(glob: String): os.RelPath => Boolean =
    val m = FileSystems.getDefault.getPathMatcher(s"glob:$glob")
    p => m.matches(p.toNIO)
