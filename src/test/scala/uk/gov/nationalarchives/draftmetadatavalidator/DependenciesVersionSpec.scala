package uk.gov.nationalarchives.draftmetadatavalidator

import org.scalatest.flatspec.AnyFlatSpec
import uk.gov.nationalarchives.draftmetadatavalidator.utils.DependencyVersionReader.{findDependencyVersion, findJarFilePath, getDependencyVersion}

class DependenciesVersionSpec extends AnyFlatSpec {

  "dependencies version reader" should "read version of the da-metadata-schema" in {

    val containingFilePath = "metadata-schema/baseSchema.schema.json"
    val version: Option[String] = findDependencyVersion(containingFilePath)
    assert(version.isDefined)
  }

}
