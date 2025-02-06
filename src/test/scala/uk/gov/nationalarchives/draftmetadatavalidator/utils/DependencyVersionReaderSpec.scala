package uk.gov.nationalarchives.draftmetadatavalidator.utils

import org.scalatest.flatspec.AnyFlatSpec
import uk.gov.nationalarchives.draftmetadatavalidator.utils.DependencyVersionReader.findDependencyVersion
import uk.gov.nationalarchives.tdr.validation.schema.JsonSchemaDefinition.BASE_SCHEMA

class DependencyVersionReaderSpec extends AnyFlatSpec {

  "dependencies version reader" should "read version of the da-metadata-schema" in {
    val containingFilePath = BASE_SCHEMA.schemaLocation
    val version: Option[String] = findDependencyVersion(containingFilePath)
    assert(version.isDefined)
  }

}
