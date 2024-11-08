package uk.gov.nationalarchives.draftmetadatavalidator

import graphql.codegen.GetCustomMetadata.customMetadata.CustomMetadata
import graphql.codegen.types.{AddOrUpdateFileMetadata, AddOrUpdateMetadata, DataType}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import uk.gov.nationalarchives.draftmetadatavalidator.utils.MetadataUtils
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.util.UUID

class MetadataUtilsSpec extends AnyFlatSpec with BeforeAndAfterEach {

  val filePath: String = getClass.getResource("/sample-for-csv-handler.csv").getPath
  val metadataNames: List[String] = List("ClosureStatus", "ClosurePeriod")

  "filterProtectedFields" should "filter out non editable Fields from the input" in {
    val customMetadata: List[CustomMetadata] = List(
      TestUtils.createCustomMetadata("ClosurePeriod", "Closure period", 1, DataType.Integer),
      TestUtils.createCustomMetadata("SHA256ClientSideChecksum", "Checksum", 2, DataType.Text, editable = false),
      TestUtils.createCustomMetadata("ClosureStatus", "Closure status", 3, DataType.Text)
    )

    val fileId = "16b2f65c-ec50-494b-824b-f8c08e6b575c"
    val fileRows = List(
      FileRow(fileId, List(Metadata("ClosurePeriod", "10"), Metadata("SHA256ClientSideChecksum", "ChecksumValue"), Metadata("ClosureStatus", "Closed")))
    )

    val filterProtectedFields = MetadataUtils.filterProtectedFields(customMetadata, fileRows)
    val expected: List[AddOrUpdateFileMetadata] = List(
      AddOrUpdateFileMetadata(
        UUID.fromString(fileId),
        List(AddOrUpdateMetadata("ClosurePeriod", "10"), AddOrUpdateMetadata("ClosureStatus", "Closed"))
      )
    )

    filterProtectedFields should be(expected)
  }
}
