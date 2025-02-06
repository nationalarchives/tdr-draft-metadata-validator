package uk.gov.nationalarchives.draftmetadatavalidator

import org.scalatest.flatspec.AnyFlatSpec
import uk.gov.nationalarchives.draftmetadatavalidator.utils.DependencyVersionReader.{findDependencyVersion, findJarFilePath, getDependencyVersion}
import uk.gov.nationalarchives.tdr.validation.schema.JsonSchemaDefinition.BASE_SCHEMA

class DependenciesVersionSpec extends AnyFlatSpec {

  "dependencies version reader" should "read version of the da-metadata-schema" in {

    val containingFilePath = BASE_SCHEMA.schemaLocation
    val version: Option[String] = findDependencyVersion(containingFilePath)
    assert(version.isDefined)
  }

}
