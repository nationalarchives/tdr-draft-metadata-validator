package uk.gov.nationalarchives.draftmetadatavalidator

import graphql.codegen.GetConsignmentFilesMetadata.getConsignmentFilesMetadata.{Data, GetConsignment}
import graphql.codegen.GetConsignmentFilesMetadata.getConsignmentFilesMetadata.GetConsignment.Files
import graphql.codegen.GetConsignmentFilesMetadata.getConsignmentFilesMetadata.GetConsignment.Files.FileMetadata
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID
import uk.gov.nationalarchives.draftmetadatavalidator.Lambda.ValidationParameters
import uk.gov.nationalarchives.tdr.validation.schema.JsonSchemaDefinition.{BASE_SCHEMA, CLOSURE_SCHEMA_CLOSED, CLOSURE_SCHEMA_OPEN}

class IdentityUtilsSpec extends AnyFlatSpec with Matchers {

  val validationParameters = ValidationParameters(
    consignmentId = UUID.fromString("a060c57d-1639-4828-9a7a-67a7c64dbf6c"),
    schemaToValidate = Set(BASE_SCHEMA, CLOSURE_SCHEMA_CLOSED, CLOSURE_SCHEMA_OPEN),
    clientAlternateKey = "tdrFileHeader",
    persistenceAlternateKey = "tdrDataLoadHeader",
    uniqueAssetIDKey = "file_path"
  )

  "buildClientToPersistenceIdMap" should "return the expected map for a set of files" in {
    val fileId1 = UUID.fromString("a060c57d-1639-4828-9a7a-67a7c64dbf6c")
    val fileId2 = UUID.fromString("cbf2cba5-f1dc-45bd-ae6d-2b042336ce6c")
    val fileId3 = UUID.fromString("c4d5e0f1-f6e1-4a77-a7c0-a4317404da00")

    val files = List(
      Files(
        fileId = fileId1,
        fileName = Some("test1.docx"),
        fileStatuses = List.empty,
        fileMetadata = List(
          FileMetadata("ClientSideOriginalFilepath", "test/test1.docx"),
          FileMetadata("FileType", "File")
        )
      ),
      Files(
        fileId = fileId2,
        fileName = Some("test2.pdf"),
        fileStatuses = List.empty,
        fileMetadata = List(
          FileMetadata("ClientSideOriginalFilepath", "test/test2.pdf"),
          FileMetadata("FileType", "File")
        )
      ),
      Files(
        fileId = fileId3,
        fileName = Some("test3.txt"),
        fileStatuses = List.empty,
        fileMetadata = List(
          FileMetadata("ClientSideOriginalFilepath", "test/test3.txt"),
          FileMetadata("FileType", "File")
        )
      )
    )

    val response = Some(Data(Some(GetConsignment(files = files, consignmentReference = "TDR-123"))))

    val expectedMap = Map(
      "test/test1.docx" -> fileId1,
      "test/test2.pdf" -> fileId2,
      "test/test3.txt" -> fileId3
    )

    IdentityUtils.buildClientToPersistenceIdMap(response, validationParameters) shouldBe expectedMap
  }

  it should "not include folders in the lookup map" in {
    val fileId1 = UUID.fromString("a060c57d-1639-4828-9a7a-67a7c64dbf6c")
    val fileId2 = UUID.fromString("cbf2cba5-f1dc-45bd-ae6d-2b042336ce6c")

    val files = List(
      Files(
        fileId = fileId1,
        fileName = Some("test1.docx"),
        fileStatuses = List.empty,
        fileMetadata = List(
          FileMetadata("ClientSideOriginalFilepath", "test/test1.docx"),
          FileMetadata("FileType", "File")
        )
      ),
      Files(
        fileId = fileId2,
        fileName = Some("test"),
        fileStatuses = List.empty,
        fileMetadata = List(
          FileMetadata("ClientSideOriginalFilepath", "test"),
          FileMetadata("FileType", "Folder")
        )
      )
    )

    val response = Some(Data(Some(GetConsignment(files = files, consignmentReference = "TDR-123"))))

    val expectedMap = Map(
      "test/test1.docx" -> fileId1
    )

    IdentityUtils.buildClientToPersistenceIdMap(response, validationParameters) shouldBe expectedMap
  }

  it should "return empty map when file id data response is empty" in {
    val response = None

    IdentityUtils.buildClientToPersistenceIdMap(response, validationParameters) shouldBe Map.empty
  }

  it should "return empty map when consignment is empty" in {
    val response = Some(Data(None))
    IdentityUtils.buildClientToPersistenceIdMap(response, validationParameters) shouldBe Map.empty
  }

  it should "return empty map when consignment contains no files" in {
    val response = Some(Data(Some(GetConsignment(files = List.empty, consignmentReference = "TDR-123"))))

    IdentityUtils.buildClientToPersistenceIdMap(response, validationParameters) shouldBe Map.empty
  }
}
