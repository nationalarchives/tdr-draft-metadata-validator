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

    val fileId = "cbf2cba5-f1dc-45bd-ae6d-2b042336ce6c"
    val fileData = FileData(
      List(
        List("Filename", "Filepath", "Closure status", "Closure Period", "SHA256ClientSideChecksum"),
        List("file1.jpg", "test/file1.jpg", "Closed", "10", "ChecksumValue")
      ),
      List(
        FileRow("test/file1.jpg", List(Metadata("ClosurePeriod", "10"), Metadata("SHA256ClientSideChecksum", "ChecksumValue"), Metadata("ClosureStatus", "Closed")))
      )
    )

    val uniqueRowKeyToFileId = Map("test/file1.jpg" -> UUID.fromString(fileId))

    val filterProtectedFields = MetadataUtils.filterProtectedFields(customMetadata, fileData, uniqueRowKeyToFileId)
    val expected: List[AddOrUpdateFileMetadata] = List(
      AddOrUpdateFileMetadata(
        UUID.fromString(fileId),
        List(AddOrUpdateMetadata("ClosurePeriod", "10"), AddOrUpdateMetadata("ClosureStatus", "Closed"))
      )
    )

    filterProtectedFields should be(expected)
  }
}
