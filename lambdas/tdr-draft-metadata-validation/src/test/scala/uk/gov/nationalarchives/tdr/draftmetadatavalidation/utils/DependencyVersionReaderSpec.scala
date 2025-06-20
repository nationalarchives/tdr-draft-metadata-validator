package uk.gov.nationalarchives.tdr.draftmetadatavalidation.utils

import org.scalatest.flatspec.AnyFlatSpec
import DependencyVersionReader.findDependencyVersion

class DependencyVersionReaderSpec extends AnyFlatSpec {

  "dependencies version reader" should "read version of the da-metadata-schema" in {
    val version: Option[String] = findDependencyVersion
    assert(version.isDefined)
  }

}
