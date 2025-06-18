package uk.gov.nationalarchives.draftmetadata.utils

import graphql.codegen.GetCustomMetadata.customMetadata.CustomMetadata
import graphql.codegen.types.{AddOrUpdateFileMetadata, AddOrUpdateMetadata, DataType}
import graphql.codegen.types.PropertyType.Defined
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import uk.gov.nationalarchives.draftmetadata.{FileRow, Metadata}

import java.util.UUID

class MetadataUtilsSpec extends AnyFlatSpec with BeforeAndAfterEach {

  val metadataNames: List[String] = List("ClosureStatus", "ClosurePeriod")

  // Test helper method to create CustomMetadata for tests
  private def createCustomMetadata(name: String, displayName: String, order: Int, dataType: DataType, editable: Boolean = true): CustomMetadata = {
    CustomMetadata(
      name,
      None,
      Some(displayName),
      Defined,
      Some("MandatoryClosure"),
      dataType,
      editable,
      multiValue = false,
      Some("Open"),
      order,
      Nil,
      Some(order),
      allowExport = true
    )
  }

  // Simple test case class to represent file details
  case class TestFileDetail(fileId: UUID, someOtherField: Option[String], anotherField: Option[Int])

  "filterProtectedFields" should "filter out non editable Fields from the input" in {
    val customMetadata: List[CustomMetadata] = List(
      createCustomMetadata("ClosurePeriod", "Closure period", 1, DataType.Integer),
      createCustomMetadata("SHA256ClientSideChecksum", "Checksum", 2, DataType.Text, editable = false),
      createCustomMetadata("ClosureStatus", "Closure status", 3, DataType.Text)
    )
    val clientFileId = "test/test.docx"
    val persistenceFileId = "16b2f65c-ec50-494b-824b-f8c08e6b575c"
    val fileWithUniqueAssetIdKey = Map(clientFileId -> TestFileDetail(UUID.fromString(persistenceFileId), None, None))
    val fileRows = List(
      FileRow(clientFileId, List(Metadata("ClosurePeriod", "10"), Metadata("SHA256ClientSideChecksum", "ChecksumValue"), Metadata("ClosureStatus", "Closed")))
    )

    val filterProtectedFields = MetadataUtils.filterProtectedFields(customMetadata, fileRows, fileWithUniqueAssetIdKey)(_.fileId)
    val expected: List[AddOrUpdateFileMetadata] = List(
      AddOrUpdateFileMetadata(
        UUID.fromString(persistenceFileId),
        List(AddOrUpdateMetadata("ClosurePeriod", "10"), AddOrUpdateMetadata("ClosureStatus", "Closed"))
      )
    )

    filterProtectedFields should be(expected)
  }
}
