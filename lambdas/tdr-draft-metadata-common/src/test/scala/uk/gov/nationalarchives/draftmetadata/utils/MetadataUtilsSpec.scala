package uk.gov.nationalarchives.draftmetadata.utils

import graphql.codegen.types.{AddOrUpdateFileMetadata, AddOrUpdateMetadata}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.util.UUID

class MetadataUtilsSpec extends AnyFlatSpec with BeforeAndAfterEach {

  val metadataNames: List[String] = List("ClosureStatus", "ClosurePeriod")

  // Simple test case class to represent file details
  case class TestFileDetail(fileId: UUID, someOtherField: Option[String], anotherField: Option[Int])

  "filterProtectedFields" should "filter out non editable Fields from the input" in {
    val clientFileId = "test/test.docx"
    val persistenceFileId = "16b2f65c-ec50-494b-824b-f8c08e6b575c"
    val fileWithUniqueAssetIdKey = Map(clientFileId -> TestFileDetail(UUID.fromString(persistenceFileId), None, None))
    val fileRows = List(
      FileRow(clientFileId, List(Metadata("ClosurePeriod", "10"), Metadata("SHA256ClientSideChecksum", "ChecksumValue"), Metadata("ClosureStatus", "Closed")))
    )

    val filterProtectedFields = MetadataUtils.filterProtectedFields(fileRows, fileWithUniqueAssetIdKey)(_.fileId)
    val expected: List[AddOrUpdateFileMetadata] = List(
      AddOrUpdateFileMetadata(
        UUID.fromString(persistenceFileId),
        List(AddOrUpdateMetadata("ClosurePeriod", "10"), AddOrUpdateMetadata("ClosureStatus", "Closed"))
      )
    )

    filterProtectedFields should be(expected)
  }
}
