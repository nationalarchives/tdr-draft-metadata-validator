package uk.gov.nationalarchives.draftmetadatavalidator.utils

import org.scalatest.flatspec.AnyFlatSpec
import uk.gov.nationalarchives.draftmetadatavalidator.utils.DependencyVersionReader.findDependencyVersion

class DependencyVersionReaderSpec extends AnyFlatSpec {

  "dependencies version reader" should "read version of the da-metadata-schema" in {
    val version: Option[String] = findDependencyVersion
    assert(version.isDefined)
  }

}
