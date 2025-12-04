import Dependencies.*
import sbtassembly.AssemblyPlugin

ThisBuild / scalaVersion := "2.13.18"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "uk.gov.nationalarchives"

val commonMergeStrategy: String => sbtassembly.MergeStrategy = {
  case PathList("META-INF", "MANIFEST.MF")           => MergeStrategy.discard
  case PathList("META-INF", "services", xs @ _*)     => MergeStrategy.concat
  case PathList("META-INF", xs @ _*)                 => MergeStrategy.discard
  case PathList(xs @ _*) if xs.last.endsWith(".nir") => MergeStrategy.discard
  case "utf8.json"                                   => MergeStrategy.discard
  case PathList("java", _ @_*)                       => MergeStrategy.discard
  case PathList("javax", _ @_*)                      => MergeStrategy.discard
  case PathList("scala", "scalanative", _ @_*)       => MergeStrategy.discard
  case PathList("scala-native", _ @_*)               => MergeStrategy.discard
  case PathList("assets", "swagger", "ui", _ @_*)    => MergeStrategy.discard
  case PathList("wiremock", _ @_*)                   => MergeStrategy.discard
  case "module-info.class"                           => MergeStrategy.discard
  case "reference.conf"                              => MergeStrategy.concat
  case x                                             => MergeStrategy.first
}

// Common assembly merge strategy
ThisBuild / assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "MANIFEST.MF")       => MergeStrategy.discard
  case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat
  case PathList("META-INF", xs @ _*)             => MergeStrategy.discard
  case "module-info.class"                       => MergeStrategy.discard
  case "reference.conf"                          => MergeStrategy.concat
  case _                                         => MergeStrategy.first
}

// Common test settings
ThisBuild / Test / fork := true
ThisBuild / Test / envVars := Map(
  "AWS_ACCESS_KEY_ID" -> "test",
  "AWS_SECRET_ACCESS_KEY" -> "test",
  "AWS_REQUEST_CHECKSUM_CALCULATION" -> "when_required",
  "AWS_RESPONSE_CHECKSUM_CALCULATION" -> "when_required"
)

// Common code module used by the lambda functions
lazy val tdrDraftMetadataCommon = (project in file("lambdas/tdr-draft-metadata-common"))
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "tdr-draft-metadata-common",
    libraryDependencies ++= Seq(
      scalaCsv,
      typeSafeConfig,
      awsLambda,
      awsLambdaJavaEvents,
      awsSsm,
      metadataValidation,
      metadataSchema,
      generatedGraphql,
      graphqlClient,
      authUtils,
      s3Utils,
      log4catsSlf4j,
      slf4jSimple,
      circeGeneric,
      circeCore,
      circeParser,
      circeGenericExtras,
      utf8Validator,
      scalaTest % Test,
      mockitoScala % Test,
      mockitoScalaTest % Test,
      scalaLogging
    ),
    assembly / skip := true
  )

lazy val tdrDraftMetadataPersistence = (project in file("lambdas/tdr-draft-metadata-persistence"))
  .enablePlugins(AssemblyPlugin)
  .dependsOn(tdrDraftMetadataCommon % "test->test;compile->compile")
  .settings(
    name := "tdr-draft-metadata-persistence",
    excludeDependencies ++= Seq(
      ExclusionRule("com.networknt", "json-schema-validator"),
      ExclusionRule("com.networknt", "openapi-parser")
    ),
    assembly / skip := false,
    assembly / assemblyJarName := "draft-metadata-persistence.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("validation-messages", _ @_*) => MergeStrategy.discard
      case x                                      => commonMergeStrategy(x)
    }
  )

lazy val tdrDraftMetadataChecks = (project in file("lambdas/tdr-draft-metadata-checks"))
  .enablePlugins(AssemblyPlugin)
  .dependsOn(tdrDraftMetadataCommon % "test->test;compile->compile")
  .settings(
    name := "tdr-draft-metadata-checks",
    assembly / skip := false,
    assembly / assemblyJarName := "draft-metadata-checks.jar",
    assembly / assemblyMergeStrategy := commonMergeStrategy,
    Compile / resourceGenerators += Def.task {
      val file = (Compile / resourceManaged).value / "metadata-schema-version.conf"
      IO.write(file, "metadataSchemaVersion = \"" + metadataSchemaVersion + "\"")
      Seq(file)
    }.taskValue
  )

// Root: disable assembly
lazy val root = (project in file("."))
  .disablePlugins(AssemblyPlugin)
  .aggregate(tdrDraftMetadataCommon, tdrDraftMetadataPersistence, tdrDraftMetadataChecks)
  .settings(
    name := "tdr-draft-metadata-validator-root",
    publish / skip := true,
    assembly / skip := true
  )
