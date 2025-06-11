import Dependencies.*

ThisBuild / scalaVersion := "2.13.16"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "uk.gov.nationalarchives"

// Common assembly merge strategy
ThisBuild / assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "MANIFEST.MF")       => MergeStrategy.discard
  case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat
  case PathList("META-INF", xs @ _*)             => MergeStrategy.discard
  case "module-info.class"                       => MergeStrategy.discard // Added for Java 9+ module system
  case "reference.conf"                          => MergeStrategy.concat
  case _                                         => MergeStrategy.first
}

// Common test settings
ThisBuild / Test / fork := true
ThisBuild / Test / envVars := Map("AWS_ACCESS_KEY_ID" -> "test", "AWS_SECRET_ACCESS_KEY" -> "test",
  "AWS_REQUEST_CHECKSUM_CALCULATION" -> "when_required", "AWS_RESPONSE_CHECKSUM_CALCULATION" -> "when_required")

lazy val tdrDraftMetadataPersistor = (project in file("lambdas/tdr-draft-metadata-persistor"))
  .settings(
    name := "tdr-draft-metadata-persistor",
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
      circeGenericExtras,
      circeGeneric,
      circeCore,
      circeParser,
      utf8Validator,
      scalaTest % Test,
      mockitoScala % Test,
      mockitoScalaTest % Test
    ),
    assembly / assemblyJarName := "tdr-draft-metadata-persistor.jar",
    Compile / resourceGenerators += Def.task {
      val file = (Compile / resourceManaged).value / "metadata-schema-version.conf"
      IO.write(file, "metadataSchemaVersion = \"" + metadataSchemaVersion + "\"")
      Seq(file)
    }.taskValue,
    Test / javaOptions += s"-Dconfig.file=${(Test / sourceDirectory).value}/resources/application.conf" // Added for persistor
  )

// Corrected second lambda definition based on workspace structure
lazy val tdrDraftMetadataValidator = (project in file("lambdas/tdr-draft-metadata-validator")) // Corrected path
  .settings(
    name := "tdr-draft-metadata-validator", // Using a more descriptive name
    libraryDependencies ++= Seq(
      // REVIEW AND ADJUST these dependencies for your validator lambda
      // Copied from persistor as a starting point, remove/add as needed:
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
      circeGenericExtras,
      circeGeneric,
      circeCore,
      circeParser,
      utf8Validator,
      scalaTest % Test,
      mockitoScala % Test,
      mockitoScalaTest % Test
    ),
    assembly / assemblyJarName := "tdr-draft-metadata-validator.jar",
    Compile / resourceGenerators += Def.task {
      val file = (Compile / resourceManaged).value / "metadata-schema-version.conf"
      IO.write(file, "metadataSchemaVersion = \"" + metadataSchemaVersion + "\"")
      Seq(file)
    }.taskValue,
    Test / javaOptions += s"-Dconfig.file=${(Test / sourceDirectory).value}/resources/application.conf" // Added for validator
    // Add resourceGenerators here if this lambda also needs the metadata-schema-version.conf or similar
  )

lazy val root = (project in file("."))
  .aggregate(tdrDraftMetadataPersistor, tdrDraftMetadataValidator) // Ensure this aggregates the correct lambda project
  .settings(
    name := "tdr-draft-metadata-validator-root", // Root project name
    publish / skip := true // Typically, you don't publish the root aggregator
  )
