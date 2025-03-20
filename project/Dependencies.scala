import sbt.*

object Dependencies {

  private val log4CatsVersion = "2.7.0"
  private val mockitoScalaVersion = "1.17.37"
  private val circeVersion = "0.14.10"
  val metadataSchemaVersion = "0.0.46"

  lazy val scalaCsv = "com.github.tototoshi" %% "scala-csv" % "2.0.0"
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.19"
  lazy val metadataValidation = "uk.gov.nationalarchives" %% "tdr-metadata-validation" % "0.0.110" exclude ("uk.gov.nationalarchives", "da-metadata-schema_3")
  lazy val schemaUtils = "uk.gov.nationalarchives" %% "tdr-schema-utils" % "0.0.110"
  lazy val metadataSchema = "uk.gov.nationalarchives" % "da-metadata-schema_3" % metadataSchemaVersion
  lazy val generatedGraphql = "uk.gov.nationalarchives" %% "tdr-generated-graphql" % "0.0.402"
  lazy val graphqlClient = "uk.gov.nationalarchives" %% "tdr-graphql-client" % "0.0.219"
  lazy val authUtils = "uk.gov.nationalarchives" %% "tdr-auth-utils" % "0.0.234"
  lazy val typeSafeConfig = "com.typesafe" % "config" % "1.4.3"
  lazy val awsLambda = "com.amazonaws" % "aws-lambda-java-core" % "1.2.3"
  lazy val awsLambdaJavaEvents = "com.amazonaws" % "aws-lambda-java-events" % "3.15.0"
  lazy val s3Utils = "uk.gov.nationalarchives" %% "s3-utils" % "0.1.235"
  lazy val awsSsm = "software.amazon.awssdk" % "ssm" % "2.26.27"
  lazy val log4catsSlf4j = "org.typelevel" %% "log4cats-slf4j" % log4CatsVersion
  lazy val slf4jSimple = "org.slf4j" % "slf4j-simple" % "2.0.17"
  lazy val mockitoScala = "org.mockito" %% "mockito-scala" % mockitoScalaVersion
  lazy val mockitoScalaTest = "org.mockito" %% "mockito-scala-scalatest" % mockitoScalaVersion
  lazy val circeCore = "io.circe" %% "circe-core" % circeVersion
  lazy val circeGeneric = "io.circe" %% "circe-generic" % circeVersion
  lazy val circeParser = "io.circe" %% "circe-parser" % circeVersion
  lazy val circeGenericExtras = "io.circe" %% "circe-generic-extras" % "0.14.4"
  lazy val utf8Validator = "uk.gov.nationalarchives" % "utf8-validator" % "1.2"
}
