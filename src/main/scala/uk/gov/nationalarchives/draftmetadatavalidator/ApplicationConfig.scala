package uk.gov.nationalarchives.draftmetadatavalidator

import com.typesafe.config.{ConfigFactory, Config => TypeSafeConfig}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

object ApplicationConfig {

  val configFactory: TypeSafeConfig = ConfigFactory.load
  val authUrl: String = configFactory.getString("auth.url")
  val apiUrl: String = configFactory.getString("api.url")
  val clientSecretPath: String = configFactory.getString("auth.clientSecretPath")
  val clientId: String = configFactory.getString("auth.clientId")
  val endpoint: String = configFactory.getString("ssm.endpoint")
  val s3Endpoint: String = configFactory.getString("s3.endpoint")
  val bucket: String = configFactory.getString("s3.draftMetadataBucket")
  val rootDirectory: String = configFactory.getString("root.directory")
  val fileName: String = configFactory.getString("draftMetadata.fileName")
  val errorFileName: String = configFactory.getString("draftMetadata.errorFileName")
  val timeToLiveSecs: Int = 60
  val graphqlApiRequestTimeOut: FiniteDuration = 180.seconds
  val batchSizeForMetadataDatabaseWrites: Int = configFactory.getInt("database.write.batchSizeForMetadata")
  val maxConcurrencyForMetadataDatabaseWrites: Int = configFactory.getInt("database.write.maxConcurrencyForMetadata")
}
