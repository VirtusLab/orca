package orca

// Re-export the tapir Schema and jsoniter-scala ConfiguredJsonValueCodec so flow
// scripts can write `import orca.*` and derive them without two extra imports.
// The val aliases forward to the source objects, which is what `derives` needs
// when it resolves `Schema.derived` / `ConfiguredJsonValueCodec.derived`.

type Schema[A] = sttp.tapir.Schema[A]
val Schema: sttp.tapir.Schema.type = sttp.tapir.Schema

type ConfiguredJsonValueCodec[A] =
  com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec[A]
val ConfiguredJsonValueCodec
    : com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec.type =
  com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
