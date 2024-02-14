import Dependencies._

ThisBuild / scalaVersion     := "2.13.10"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "uk.gov.nationalarchives"

lazy val root = (project in file("."))
  .settings(
    name := "tdr-draft-metadata-validator",
    libraryDependencies ++= Seq(
      scalaCsv,
      typeSafeConfig,
      awsLambda,
      awsSsm,
      metadataValidation,
      generatedGraphql,
      graphqlClient,
      authUtils,
      s3Utils,
      log4catsSlf4j,
      scalaTest % Test,
    ),
    assembly / assemblyJarName := "draft-metadata-validator.jar"
  )

(assembly / assemblyMergeStrategy) := {
  case PathList("META-INF", xs@_*) => MergeStrategy.discard
  case _ => MergeStrategy.first
}

(Test / fork) := true
(Test / javaOptions) += s"-Dconfig.file=${sourceDirectory.value}/test/resources/application.conf"
(Test / envVars) := Map("AWS_ACCESS_KEY_ID" -> "test", "AWS_SECRET_ACCESS_KEY" -> "test", "S3_ENDPOINT" -> "http://localhost:9005")
