package uk.gov.nationalarchives.tdr.draftmetadatachecks

import com.amazonaws.services.lambda.runtime.Context
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.stubbing.{ServeEvent, StubMapping}
import graphql.codegen.AddOrUpdateBulkFileMetadata.{addOrUpdateBulkFileMetadata => afm}
import graphql.codegen.UpdateConsignmentMetadataSchemaLibraryVersion.{updateConsignmentMetadataSchemaLibraryVersion => ucslv}
import graphql.codegen.UpdateConsignmentStatus.{updateConsignmentStatus => ucs}
import graphql.codegen.types._
import io.circe.generic.auto._
import io.circe.parser.decode
import org.mockito.MockitoSugar.mock
import org.scalatest.matchers.must.Matchers.{be, convertToAnyMustWrapper}
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import uk.gov.nationalarchives.draftmetadata.FileTestData
import uk.gov.nationalarchives.draftmetadata.TestUtils.{fileTestData, filesWithUniquesAssetIdKeyResponse}

import java.nio.file.{Files, Paths}
import java.text.SimpleDateFormat
import java.util.{Date, UUID}
import scala.io.Source
import scala.jdk.CollectionConverters.{CollectionHasAsScala, MapHasAsJava}

class LambdaSpec extends ExternalServicesSpec {

  val consignmentId: Object = "f82af3bf-b742-454c-9771-bfd6c5eae749"
  val mockContext: Context = mock[Context]
  val pattern = "yyyy-MM-dd"
  val dateFormat = new SimpleDateFormat(pattern)

  private def checkFileError(errorFile: String) = {
    mockS3ErrorFilePutResponse()
    val input = Map("consignmentId" -> consignmentId).asJava
    new Lambda().handleRequest(input, mockContext)

    val s3Interactions: Iterable[ServeEvent] = wiremockS3.getAllServeEvents.asScala.filter(serveEvent => serveEvent.getRequest.getMethod == RequestMethod.PUT).toList
    s3Interactions.size shouldBe 1

    val errorWriteRequest = s3Interactions.head
    val rawErrorFileData = errorWriteRequest.getRequest.getBodyAsString

    // Extract just the JSON part from between the first { and last }
    val errorFileData = rawErrorFileData.substring(
      rawErrorFileData.indexOf("{"),
      rawErrorFileData.lastIndexOf("}") + 1
    )

    val today = dateFormat.format(new Date)
    val expectedErrorData: String = Source.fromResource(errorFile).getLines.mkString(System.lineSeparator()).replace("$today", today)
    errorFileData should be(expectedErrorData)
  }

  private def validateAndSaveMetadata(csvFile: String, errorFile: String, statusValue: String): Unit = {
    authOkJson()
    graphqlOkJson(saveMetadata = true, filesWithUniquesAssetIdKeyResponse(fileTestData))
    mockS3GetResponse(csvFile)
    mockS3ErrorFilePutResponse()
    val input = Map("consignmentId" -> consignmentId).asJava
    new Lambda().handleRequest(input, mockContext)

    val s3Interactions = wiremockS3.getAllServeEvents.asScala.filter(_.getRequest.getMethod == RequestMethod.PUT).toList
    s3Interactions.size shouldBe 1

    val errorFileData = s3Interactions.head.getRequest.getBodyAsString
    val today = dateFormat.format(new Date)
    val expectedErrorData = Source.fromResource(errorFile).getLines.mkString(System.lineSeparator()).replace("$today", today)
    errorFileData shouldBe expectedErrorData

    val updateConsignmentStatusInput = decode[UpdateConsignmentStatusGraphqlRequestData](
      getServeEvent("updateConsignmentStatus").get.getRequest.getBodyAsString
    ).getOrElse(UpdateConsignmentStatusGraphqlRequestData("", ucs.Variables(ConsignmentStatusInput(UUID.fromString(consignmentId.toString), "", None, None))))
      .variables
      .updateConsignmentStatusInput

    val addOrUpdateBulkFileMetadataInput = decode[AddOrUpdateBulkFileMetadataGraphqlRequestData](
      getServeEvent("addOrUpdateBulkFileMetadata").get.getRequest.getBodyAsString
    ).getOrElse(AddOrUpdateBulkFileMetadataGraphqlRequestData("", afm.Variables(AddOrUpdateBulkFileMetadataInput(UUID.fromString(consignmentId.toString), Nil))))
      .variables
      .addOrUpdateBulkFileMetadataInput

    val updateConsignmentMetadataSchemaLibraryVersion = decode[UpdateConsignmentMetadataSchemaLibraryVersionGraphqlRequestData](
      getServeEvent("updateMetadataSchemaLibraryVersion").get.getRequest.getBodyAsString
    ).getOrElse(
      UpdateConsignmentMetadataSchemaLibraryVersionGraphqlRequestData(
        "",
        ucslv.Variables(UpdateMetadataSchemaLibraryVersionInput(UUID.fromString(consignmentId.toString), "failed"))
      )
    ).variables
      .updateMetadataSchemaLibraryVersionInput

    addOrUpdateBulkFileMetadataInput.fileMetadata.size should be(3)
    addOrUpdateBulkFileMetadataInput.fileMetadata should be(expectedFileMetadataInput(fileTestData))

    updateConsignmentStatusInput.statusType must be("DraftMetadata")
    updateConsignmentStatusInput.statusValue must be(Some(statusValue))
    updateConsignmentMetadataSchemaLibraryVersion.metadataSchemaLibraryVersion mustNot be("failed")
    updateConsignmentMetadataSchemaLibraryVersion.metadataSchemaLibraryVersion mustNot be("Failed to get schema library version")
  }

  "handleRequest" should "download the draft metadata csv file, validate, save empty error file to s3 if it has no errors" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = filesWithUniquesAssetIdKeyResponse(fileTestData))
    mockS3GetResponse("sample.csv")
    checkFileError("json/empty-error-file.json")
  }

  "handleRequest" should "download the draft metadata csv file, check for schema errors and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = filesWithUniquesAssetIdKeyResponse(fileTestData))
    mockS3GetResponse("invalid-sample.csv")
    checkFileError("json/error-file.json")
  }

  "handleRequest" should "download the draft metadata csv file, validate protected fields and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = filesWithUniquesAssetIdKeyResponse(fileTestData))
    mockS3GetResponse("sample-updated-protected-fields.csv")
    checkFileError("json/error-file-protected-fields.json")
  }

  "handleRequest" should "download the draft metadata csv file, check for duplicate columns and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = filesWithUniquesAssetIdKeyResponse(fileTestData))
    mockS3GetResponse("sample-duplicate-headers.csv")
    checkFileError("json/error-file-duplicate-headers.json")
  }

  "handleRequest" should "download the draft metadata csv file, check for additional columns and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = filesWithUniquesAssetIdKeyResponse(fileTestData))
    mockS3GetResponse("sample-additional-headers.csv")
    checkFileError("json/error-file-additional-headers.json")
  }

  "handleRequest" should "download the draft metadata csv file, validate the required and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = filesWithUniquesAssetIdKeyResponse(fileTestData))
    mockS3GetResponse("sample-missing-required-column.csv")
    checkFileError("json/error-file-required.json")
  }

  "handleRequest" should "download the a draft metadata csv file with non-UTF8 character, validate it as a UTF-8 and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = filesWithUniquesAssetIdKeyResponse(fileTestData))
    mockS3GetResponse("sample-not-utf8.csv")
    checkFileError("json/not-utf8-error.json")
  }

  "handleRequest" should "download the a draft metadata csv file with no key value column try and load and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = filesWithUniquesAssetIdKeyResponse(fileTestData))
    mockS3GetResponse("sample-no-match-column.csv")
    checkFileError("json/no-match-col-error.json")
  }

  "handleRequest" should "download the draft metadata csv file with invalid key, validate it and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = filesWithUniquesAssetIdKeyResponse(fileTestData))
    mockS3GetResponse("sample-invalid-column-key.csv")
    checkFileError("json/no-match-col-error.json")
  }

  "handleRequest" should "download the draft metadata csv file with duplicate file row errors, validate it and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = filesWithUniquesAssetIdKeyResponse(fileTestData))
    mockS3GetResponse("sample-invalid-rows-duplicate.csv")
    checkFileError("json/error-file-invalid-rows-duplicate.json")
  }

  "handleRequest" should "download the draft metadata csv file with validation errors and duplicate file rows, validate it and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = filesWithUniquesAssetIdKeyResponse(fileTestData))
    mockS3GetResponse("sample-validation-error-invalid-rows-duplicate.csv")
    checkFileError("json/error-file-validation-error-invalid-rows-duplicate.json")
  }

  "handleRequest" should "download the draft metadata csv file with missing file row errors, validate it and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = filesWithUniquesAssetIdKeyResponse(fileTestData))
    mockS3GetResponse("sample-invalid-rows-missing.csv")
    checkFileError("json/error-file-invalid-rows-missing.json")
  }

  "handleRequest" should "download the draft metadata csv file with empty row and row errors, validate it and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = filesWithUniquesAssetIdKeyResponse(fileTestData))
    mockS3GetResponse("sample-empty-row-with-errors.csv")
    checkFileError("json/error-file-validation-errors-invalid-rows.json")
  }

  "handleRequest" should "download the draft metadata csv file with unknown file row errors, validate it and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = filesWithUniquesAssetIdKeyResponse(fileTestData))
    mockS3GetResponse("sample-invalid-rows-unknown.csv")
    checkFileError("json/error-file-validation-error-invalid-rows-unknown.json")
  }

  "handleRequest" should "download the draft metadata csv file with duplicate unknown file row errors, validate it and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = filesWithUniquesAssetIdKeyResponse(fileTestData))
    mockS3GetResponse("sample-invalid-rows-duplicate-unknown.csv")
    checkFileError("json/error-file-invalid-rows-duplicate-unknown.json")
  }

  "handleRequest" should "download the draft metadata csv with file validation and file row errors, validate it and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = filesWithUniquesAssetIdKeyResponse(fileTestData))
    mockS3GetResponse("sample-validation-errors-invalid-rows.csv")
    checkFileError("json/error-file-validation-errors-invalid-rows.json")
  }

  "handleRequest" should "download the draft metadata csv with file validation pattern errors, validate it and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = filesWithUniquesAssetIdKeyResponse(fileTestData))
    mockS3GetResponse("sample-validation-errors-pattern.csv")
    checkFileError("json/error-file-validation-errors-pattern.json")
  }

  "handleRequest" should "download the draft metadata csv with foi code period mismatch, validate it and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = filesWithUniquesAssetIdKeyResponse(fileTestData))
    mockS3GetResponse("sample-foi-code-period-mismatch.csv")
    checkFileError("json/error-file-foi-code-period-mismatch.json")
  }

  "handleRequest" should "download the draft metadata csv file, check for relationship schema errors and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = filesWithUniquesAssetIdKeyResponse(fileTestData))
    mockS3GetResponse("sample-invalid-description-with-alternate-description.csv")
    checkFileError("json/error-file-invalid-description-with-alternate-description.json")
  }

  "handleRequest" should "return correct response for a successful validation" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = filesWithUniquesAssetIdKeyResponse(fileTestData))
    mockS3GetResponse("sample.csv")
    mockS3ErrorFilePutResponse()
    val input = Map("consignmentId" -> consignmentId).asJava
    val response = new Lambda().handleRequest(input, mockContext)
    response.get("consignmentId") must be(consignmentId)
    response.get("validationStatus") must be("success")
    response.get("validationLibraryVersion") mustNot be("Failed to get schema library version")
    response.get("error") must be("")
  }

  "handleRequest" should "return correct response for a failed validation" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = filesWithUniquesAssetIdKeyResponse(fileTestData))
    mockS3GetResponse("invalid-sample.csv")
    mockS3ErrorFilePutResponse()
    val input = Map("consignmentId" -> consignmentId).asJava
    val response = new Lambda().handleRequest(input, mockContext)
    response.get("consignmentId") must be(consignmentId)
    response.get("validationStatus") must be("failure")
    response.get("validationLibraryVersion") mustNot be("Failed to get schema library version")
    response.get("error") must be("")
  }

  def mockS3GetResponse(fileName: String): StubMapping = {
    val filePath = getClass.getResource(s"/$fileName").getFile
    val bytes = Files.readAllBytes(Paths.get(filePath))
    wiremockS3.stubFor(
      get(urlEqualTo(s"/$consignmentId/sample.csv"))
        .willReturn(aResponse().withStatus(200).withBody(bytes))
    )
  }

  def mockS3ErrorFilePutResponse(): StubMapping = {
    wiremockS3.stubFor(
      put(urlEqualTo(s"/$consignmentId/draft-metadata-errors.json"))
        .willReturn(aResponse().withStatus(200))
    )
  }

  private def expectedFileMetadataInput(fileTestData: List[FileTestData]): List[AddOrUpdateFileMetadata] = {
    List(
      AddOrUpdateFileMetadata(
        fileTestData.find(_.filePath == "test/test3.txt").get.fileId,
        List(
          AddOrUpdateMetadata("DescriptionClosed", "false"),
          AddOrUpdateMetadata("FoiExemptionCode", ""),
          AddOrUpdateMetadata("DescriptionAlternate", ""),
          AddOrUpdateMetadata("former_reference_department", ""),
          AddOrUpdateMetadata("ClosurePeriod", ""),
          AddOrUpdateMetadata("TitleAlternate", ""),
          AddOrUpdateMetadata("TitleClosed", "false"),
          AddOrUpdateMetadata("file_name_translation", ""),
          AddOrUpdateMetadata("ClosureType", "Open"),
          AddOrUpdateMetadata("description", "eee"),
          AddOrUpdateMetadata("FoiExemptionAsserted", ""),
          AddOrUpdateMetadata("ClosureStartDate", ""),
          AddOrUpdateMetadata("Language", "English"),
          AddOrUpdateMetadata("end_date", "")
        )
      ),
      AddOrUpdateFileMetadata(
        fileTestData.find(_.filePath == "test/test1.txt").get.fileId,
        List(
          AddOrUpdateMetadata("DescriptionClosed", "false"),
          AddOrUpdateMetadata("FoiExemptionCode", "27(1);27(2)"),
          AddOrUpdateMetadata("DescriptionAlternate", ""),
          AddOrUpdateMetadata("former_reference_department", ""),
          AddOrUpdateMetadata("ClosurePeriod", "33;44"),
          AddOrUpdateMetadata("TitleAlternate", "title"),
          AddOrUpdateMetadata("TitleClosed", "true"),
          AddOrUpdateMetadata("file_name_translation", ""),
          AddOrUpdateMetadata("ClosureType", "Closed"),
          AddOrUpdateMetadata("description", "hello"),
          AddOrUpdateMetadata("FoiExemptionAsserted", "1990-01-01 00:00:00.0"),
          AddOrUpdateMetadata("ClosureStartDate", "1990-01-01 00:00:00.0"),
          AddOrUpdateMetadata("Language", "English"),
          AddOrUpdateMetadata("end_date", "1990-01-01 00:00:00.0")
        )
      ),
      AddOrUpdateFileMetadata(
        fileTestData.find(_.filePath == "test/test2.txt").get.fileId,
        List(
          AddOrUpdateMetadata("DescriptionClosed", "false"),
          AddOrUpdateMetadata("FoiExemptionCode", ""),
          AddOrUpdateMetadata("DescriptionAlternate", ""),
          AddOrUpdateMetadata("former_reference_department", ""),
          AddOrUpdateMetadata("ClosurePeriod", ""),
          AddOrUpdateMetadata("TitleAlternate", ""),
          AddOrUpdateMetadata("TitleClosed", "false"),
          AddOrUpdateMetadata("file_name_translation", ""),
          AddOrUpdateMetadata("ClosureType", "Open"),
          AddOrUpdateMetadata("description", "www"),
          AddOrUpdateMetadata("FoiExemptionAsserted", ""),
          AddOrUpdateMetadata("ClosureStartDate", ""),
          AddOrUpdateMetadata("Language", "English"),
          AddOrUpdateMetadata("end_date", "")
        )
      )
    )
  }
}

case class UpdateConsignmentStatusGraphqlRequestData(query: String, variables: ucs.Variables)
case class UpdateConsignmentMetadataSchemaLibraryVersionGraphqlRequestData(query: String, variables: ucslv.Variables)
case class AddOrUpdateBulkFileMetadataGraphqlRequestData(query: String, variables: afm.Variables)
