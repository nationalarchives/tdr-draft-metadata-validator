import sbt._

object Dependencies {

  private val log4CatsVersion = "2.7.0"
  private val mockitoScalaVersion = "1.17.31"

  lazy val scalaCsv = "com.github.tototoshi" %% "scala-csv" % "1.3.10"
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.18"
  lazy val metadataValidation = "uk.gov.nationalarchives" %% "tdr-metadata-validation" % "0.0.27"
  lazy val generatedGraphql = "uk.gov.nationalarchives" %% "tdr-generated-graphql" % "0.0.377"
  lazy val graphqlClient = "uk.gov.nationalarchives" %% "tdr-graphql-client" % "0.0.163"
  lazy val authUtils = "uk.gov.nationalarchives" %% "tdr-auth-utils" % "0.0.203"
  lazy val typeSafeConfig = "com.typesafe" % "config" % "1.4.3"
  lazy val awsLambda = "com.amazonaws" % "aws-lambda-java-core" % "1.2.3"
  lazy val awsLambdaJavaEvents = "com.amazonaws" % "aws-lambda-java-events" % "3.11.6"
  lazy val awsSsm = "software.amazon.awssdk" % "ssm" % "2.25.69"
  lazy val s3Utils = "uk.gov.nationalarchives" %% "s3-utils" % "0.1.181"
  lazy val log4catsSlf4j = "org.typelevel" %% "log4cats-slf4j" % log4CatsVersion
  lazy val mockitoScala = "org.mockito" %% "mockito-scala" % mockitoScalaVersion
  lazy val mockitoScalaTest = "org.mockito" %% "mockito-scala-scalatest" % mockitoScalaVersion
}
