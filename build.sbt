import Dependencies.*

ThisBuild / scalaVersion := "2.13.16"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "uk.gov.nationalarchives"

lazy val root = (project in file("."))
  .settings(
    name := "tdr-draft-metadata-validator",
    libraryDependencies ++= Seq(
      scalaCsv,
      typeSafeConfig,
      awsLambda,
      awsLambdaJavaEvents,
      awsSsm,
      metadataValidation,
      metadataSchema,
      schemaUtils,
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
    assembly / assemblyJarName := "draft-metadata-validator.jar",
    Compile / resourceGenerators += Def.task {
      val file = (Compile / resourceManaged).value / "metadata-schema-version.conf"
      IO.write(file, s"""metadataSchemaVersion = "$metadataSchemaVersion"""")
      Seq(file)
    }.taskValue
  )

(assembly / assemblyMergeStrategy) := {
  case PathList("META-INF", "MANIFEST.MF")       => MergeStrategy.discard
  case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat
  case PathList("META-INF", xs @ _*)             => MergeStrategy.discard
  case _                                         => MergeStrategy.first
}

(Test / fork) := true
(Test / javaOptions) += s"-Dconfig.file=${sourceDirectory.value}/test/resources/application.conf"
(Test / envVars) := Map("AWS_ACCESS_KEY_ID" -> "test", "AWS_SECRET_ACCESS_KEY" -> "test")
