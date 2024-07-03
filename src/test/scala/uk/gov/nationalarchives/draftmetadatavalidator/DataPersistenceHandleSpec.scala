package uk.gov.nationalarchives.draftmetadatavalidator

import graphql.codegen.GetCustomMetadata.customMetadata.CustomMetadata
import graphql.codegen.types.{DataType, PropertyType}
import org.mockito.MockitoSugar.mock
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import uk.gov.nationalarchives.draftmetadatavalidator.Lambda.DraftMetadata
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.util.UUID

class DataPersistenceHandleSpec extends ExternalServicesSpec {
  private val stubbedMetadata = List(
    Metadata("ClientSideOriginalFilepath", "test/test3.txt"),
    Metadata("SHA256ClientSideChecksum", "somechecksumTest3"),
    Metadata("ClientSideFileLastModifiedDate", "2024-05-07"),
    Metadata("ClientSideFileSize", "123"),
    Metadata("NonClientSideInput", "someValue")
  )

  private val stubbedCustomMetadata = List(
    CustomMetadata(
      "nonEditableProperty",
      None,
      None,
      PropertyType.Supplied,
      None,
      DataType.Text,
      editable = false,
      multiValue = false,
      None,
      1,
      List(),
      Some(1),
      allowExport = true
    ),
    CustomMetadata(
      "editableProperty1",
      None,
      None,
      PropertyType.Supplied,
      None,
      DataType.Text,
      editable = true,
      multiValue = false,
      None,
      1,
      List(),
      Some(1),
      allowExport = true
    ),
    CustomMetadata(
      "editableProperty2",
      None,
      None,
      PropertyType.Supplied,
      None,
      DataType.Text,
      editable = true,
      multiValue = false,
      None,
      1,
      List(),
      Some(1),
      allowExport = true
    )
  )

  private val stubbedEditableMetadata = List(
    Metadata("nonEditableProperty", "nonEditablePropertyValue"),
    Metadata("editableProperty1", "editableProperty1Value"),
    Metadata("editableProperty2", "editableProperty2Value")
  )

  "implicit FileRowHelper toClientSideMetadataInput" should "convert to row to correct input" in {
    val dataPersistence = initialiseDataPersistence

    val fileRow = FileRow("fileName", stubbedMetadata)
    val input = dataPersistence.FileRowHelper(fileRow).toClientSideMetadataInput

    input.checksum shouldBe "somechecksumTest3"
    input.fileSize shouldBe 123L
    input.lastModified shouldBe 1715036400000L
    input.originalPath shouldBe "test/test3.txt"
    input.matchId shouldBe 1L
  }

  "implicit FileRowHelper toAddOrUpdateFileMetadataInput" should "convert to row to correct input" in {
    val dataPersistence = initialiseDataPersistence
    val fileId: String = "f82af3bf-b742-454c-9771-bfd6c5eae749"

    val fileRow = FileRow(fileId, stubbedEditableMetadata)
    val input = dataPersistence.FileRowHelper(fileRow).toAddOrUpdateFileMetadataInput(stubbedCustomMetadata)

    input.fileId shouldBe UUID.fromString(fileId)
    input.metadata.size shouldBe 2

    input.metadata.head.filePropertyName shouldBe "editableProperty1"
    input.metadata.head.value shouldBe "editableProperty1Value"

    input.metadata.last.filePropertyName shouldBe "editableProperty2"
    input.metadata.last.value shouldBe "editableProperty2Value"
  }

  "implicit MetadataHelper" should "return the correct values from metadata" in {
    val dataPersistence = initialiseDataPersistence
    val helper = dataPersistence.MetadataHelper(stubbedMetadata)
    helper.originalPath shouldBe "test/test3.txt"
    helper.lastModified shouldBe 1715036400000L
    helper.checksum shouldBe "somechecksumTest3"
    helper.fileSize shouldBe 123L
  }

  "implicit MetadataHelper 'editableMetadata'" should "return only editable metadata" in {
    val dataPersistence = initialiseDataPersistence
    val result = dataPersistence.MetadataHelper(stubbedEditableMetadata).editableMetadata(stubbedCustomMetadata)

    result.size shouldBe 2
    result.head.name shouldBe "editableProperty1"
    result.head.value shouldBe "editableProperty1Value"
    result.last.name shouldBe "editableProperty2"
    result.last.value shouldBe "editableProperty2Value"
  }

  private def initialiseDataPersistence: DataPersistenceHandler = {
    val mockDraftMetadata = mock[DraftMetadata]
    val mockGraphApi = mock[GraphQlApi]
    DataPersistenceHandler.apply(mockDraftMetadata, "clientSecret", mockGraphApi)
  }
}
