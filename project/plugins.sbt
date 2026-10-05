val sbtSoftwareMillVersion = "3.0.1"
addSbtPlugin(
  "com.softwaremill.sbt-softwaremill" % "sbt-softwaremill-common" % sbtSoftwareMillVersion
)
addSbtPlugin(
  "com.softwaremill.sbt-softwaremill" % "sbt-softwaremill-publish" % sbtSoftwareMillVersion
)

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
