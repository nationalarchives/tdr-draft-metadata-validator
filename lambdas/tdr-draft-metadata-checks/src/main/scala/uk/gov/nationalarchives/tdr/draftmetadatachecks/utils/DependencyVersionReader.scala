package uk.gov.nationalarchives.tdr.draftmetadatachecks.utils

import com.typesafe.config.ConfigFactory

import scala.util.Try

object DependencyVersionReader {
  def findDependencyVersion: Option[String] = {
    Try {
      val config = ConfigFactory.load("metadata-schema-version.conf")
      config.getString("metadataSchemaVersion")
    }.toOption
  }
}
