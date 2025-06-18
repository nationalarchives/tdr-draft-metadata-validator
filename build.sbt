import Dependencies.*

ThisBuild / scalaVersion := "2.13.16"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "uk.gov.nationalarchives"

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
      // Add scalalogging
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5"
    )
  )

lazy val tdrDraftMetadataPersistor = (project in file("lambdas/tdr-draft-metadata-persistor"))
  .dependsOn(tdrDraftMetadataCommon % "test->test;compile->compile") // Updated dependency to include test dependencies
  .settings(
    name := "tdr-draft-metadata-persistor",
    excludeDependencies ++= Seq(
      ExclusionRule("com.networknt", "json-schema-validator"),
      ExclusionRule("com.networknt", "openapi-parser")
    ),
    assembly / assemblyJarName := "tdr-draft-metadata-persistor.jar",

    // Add assembly merge strategy for cats files to avoid conflicts if needed
    assembly / assemblyMergeStrategy := {
      // Discard Scala 3 IR files (.nir) which are compile-time only
      case PathList(xs @ _*) if xs.last.endsWith(".nir") => MergeStrategy.discard
      case PathList("validation-messages", _ @_*)        => MergeStrategy.discard
      case "utf8.json"                                   => MergeStrategy.discard
      case PathList("java", _ @_*)                       => MergeStrategy.discard
      case PathList("javax", _ @_*)                      => MergeStrategy.discard
      case PathList("scala", "scalanative", _ @_*)       => MergeStrategy.discard
      case PathList("scala-native", _ @_*)               => MergeStrategy.discard
      case PathList("assets", "swagger", "ui", _ @_*)    => MergeStrategy.discard
      case PathList("wiremock", _ @_*)                   => MergeStrategy.discard
      case PathList("META-INF", "MANIFEST.MF")           => MergeStrategy.discard
      case PathList("META-INF", "services", xs @ _*)     => MergeStrategy.concat
      case PathList("META-INF", xs @ _*)                 => MergeStrategy.discard
      case "module-info.class"                           => MergeStrategy.discard // Added for Java 9+ module system
      case "reference.conf"                              => MergeStrategy.concat
      case x                                             => MergeStrategy.first
    },
    Test / javaOptions += s"-Dconfig.file=${(Test / sourceDirectory).value}/resources/application.conf" // Added for persistor
  )

// Corrected second lambda definition based on workspace structure
lazy val tdrDraftMetadataValidator = (project in file("lambdas/tdr-draft-metadata-validator"))
  .dependsOn(tdrDraftMetadataCommon % "test->test;compile->compile") // Updated to include test dependencies
  .settings(
    name := "tdr-draft-metadata-validator",
    assembly / assemblyJarName := "tdr-draft-metadata-validator.jar",
    Compile / resourceGenerators += Def.task {
      val file = (Compile / resourceManaged).value / "metadata-schema-version.conf"
      IO.write(file, "metadataSchemaVersion = \"" + metadataSchemaVersion + "\"")
      Seq(file)
    }.taskValue,
    Test / javaOptions += s"-Dconfig.file=${(Test / sourceDirectory).value}/resources/application.conf"
  )

lazy val root = (project in file("."))
  .aggregate(tdrDraftMetadataCommon, tdrDraftMetadataPersistor, tdrDraftMetadataValidator) // Ensure this aggregates the correct lambda project
  .settings(
    name := "tdr-draft-metadata-validator-root", // Root project name
    publish / skip := true // Typically, you don't publish the root aggregator
  )
