package orca.shell.sessions

import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  ConfiguredJsonValueCodec
}
import orca.runner.manifest.{AttemptManifest, AttemptStatus}

/** An attempt's [[AttemptStatus]] as the shell sees it now: a manifest still
  * [[AttemptStatus.Running]] whose process is gone is `Crashed` (ADR 0021 §8).
  */
private[shell] enum ObservedStatus:
  case Running, Succeeded, Failed, Crashed

private[shell] object ObservedStatus:

  /** `processAlive` answers whether the process that wrote `manifest` still
    * runs.
    */
  def of(
      manifest: AttemptManifest,
      processAlive: AttemptManifest => Boolean
  ): ObservedStatus =
    manifest.status match
      case AttemptStatus.Running =>
        if processAlive(manifest) then Running else Crashed
      case AttemptStatus.Succeeded => Succeeded
      case AttemptStatus.Failed    => Failed

  given codec: ConfiguredJsonValueCodec[ObservedStatus] =
    ConfiguredJsonValueCodec.derived[ObservedStatus](using
      CodecMakerConfig.withDiscriminatorFieldName(None)
    )
