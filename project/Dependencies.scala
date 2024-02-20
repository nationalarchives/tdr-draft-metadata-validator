import sbt._

object Dependencies {

  private val log4CatsVersion = "2.6.0"

  lazy val scalaCsv = "com.github.tototoshi" %% "scala-csv" % "1.3.10"
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.15"
  lazy val metadataValidation = "uk.gov.nationalarchives" %% "tdr-metadata-validation" % "0.0.13"
  lazy val generatedGraphql = "uk.gov.nationalarchives" %% "tdr-generated-graphql" % "0.0.357"
  lazy val graphqlClient = "uk.gov.nationalarchives" %% "tdr-graphql-client" % "0.0.144"
  lazy val authUtils = "uk.gov.nationalarchives" %% "tdr-auth-utils" % "0.0.187"
  lazy val typeSafeConfig = "com.typesafe" % "config" % "1.4.3"
  lazy val awsLambda = "com.amazonaws" % "aws-lambda-java-core" % "1.2.3"
  lazy val awsSsm = "software.amazon.awssdk" % "ssm" % "2.23.17"
  lazy val s3Utils = "uk.gov.nationalarchives" %% "s3-utils" % "0.1.105"
  lazy val log4catsSlf4j = "org.typelevel" %% "log4cats-slf4j" % log4CatsVersion

}
