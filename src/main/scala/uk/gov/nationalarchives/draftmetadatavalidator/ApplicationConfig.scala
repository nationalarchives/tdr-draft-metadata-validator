package uk.gov.nationalarchives.draftmetadatavalidator

import com.typesafe.config.{ConfigFactory, Config => TypeSafeConfig}

object ApplicationConfig {

  val configFactory: TypeSafeConfig = ConfigFactory.load
  val authUrl: String = configFactory.getString("auth.url")
  val apiUrl: String = configFactory.getString("api.url")
  val clientSecretPath: String = configFactory.getString("auth.clientSecretPath")
  val endpoint: String = configFactory.getString("ssm.endpoint")
  val s3Endpoint: String = configFactory.getString("s3.endpoint")
  val bucket: String = configFactory.getString("s3.draftMetadataBucket")
  val rootDirectory: String = configFactory.getString("root.directory")
  val fileName: String = configFactory.getString("draftMetadata.fileName")
  val timeToLiveInSecs: Int = 60

}
