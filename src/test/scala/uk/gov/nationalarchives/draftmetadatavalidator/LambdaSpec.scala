package uk.gov.nationalarchives.draftmetadatavalidator

import com.amazonaws.services.lambda.runtime.Context
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, get, put, urlEqualTo}
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.stubbing.{ServeEvent, StubMapping}
import graphql.codegen.UpdateConsignmentStatus.{updateConsignmentStatus => ucs}
import graphql.codegen.AddOrUpdateBulkFileMetadata.{addOrUpdateBulkFileMetadata => afm}
import graphql.codegen.types.{AddOrUpdateBulkFileMetadataInput, AddOrUpdateFileMetadata, AddOrUpdateMetadata, ConsignmentStatusInput}
import io.circe.generic.auto._
import io.circe.parser.decode
import org.mockito.MockitoSugar.mock
import org.scalatest.matchers.must.Matchers.{be, convertToAnyMustWrapper}
import org.scalatest.matchers.should.Matchers.{convertToAnyShouldWrapper, equal}
import uk.gov.nationalarchives.draftmetadatavalidator.TestUtils.testFileIdMetadata

import java.nio.file.{Files, Paths}
import java.text.SimpleDateFormat
import java.util.{Date, UUID}
import scala.io.Source
import scala.jdk.CollectionConverters.{CollectionHasAsScala, MapHasAsJava}

class LambdaSpec extends ExternalServicesSpec {

  val consignmentId: Object = "f82af3bf-b742-454c-9771-bfd6c5eae749"
  val mockContext: Context = mock[Context]

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

  val pattern = "yyyy-MM-dd"
  val dateFormat = new SimpleDateFormat(pattern)

  "handleRequest" should "download the draft metadata csv file, validate, save empty error file to s3 and save metadata to db if it has no errors" in {
    authOkJson()
    val fileIdMetadata = testFileIdMetadata(Seq("test/test1.txt", "test/test2.txt", "test/test3.txt"))
    graphqlOkJson(true, fileIdMetadata)
    mockS3GetResponse("sample.csv")
    mockS3ErrorFilePutResponse()
    val input = Map("consignmentId" -> consignmentId).asJava
    val response = new Lambda().handleRequest(input, mockContext)
    response.getStatusCode should equal(200)

    val s3Interactions: Iterable[ServeEvent] = wiremockS3.getAllServeEvents.asScala.filter(serveEvent => serveEvent.getRequest.getMethod == RequestMethod.PUT).toList
    s3Interactions.size shouldBe 1

    val errorWriteRequest = s3Interactions.head
    val errorFileData = errorWriteRequest.getRequest.getBodyAsString

    val today = dateFormat.format(new Date)
    val expectedErrorData: String =
      Source.fromResource("json/empty-error-file.json").getLines.mkString(System.lineSeparator()).replace("$today", today)
    errorFileData shouldBe expectedErrorData

    val updateConsignmentStatusEvent = getServeEvent("updateConsignmentStatus").get
    val request: UpdateConsignmentStatusGraphqlRequestData = decode[UpdateConsignmentStatusGraphqlRequestData](updateConsignmentStatusEvent.getRequest.getBodyAsString)
      .getOrElse(UpdateConsignmentStatusGraphqlRequestData("", ucs.Variables(ConsignmentStatusInput(UUID.fromString(consignmentId.toString), "", None))))
    val updateConsignmentStatusInput = request.variables.updateConsignmentStatusInput

    val addOrUpdateBulkFileMetadataEvent = getServeEvent("addOrUpdateBulkFileMetadata").get
    val request2: AddOrUpdateBulkFileMetadataGraphqlRequestData = decode[AddOrUpdateBulkFileMetadataGraphqlRequestData](addOrUpdateBulkFileMetadataEvent.getRequest.getBodyAsString)
      .getOrElse(AddOrUpdateBulkFileMetadataGraphqlRequestData("", afm.Variables(AddOrUpdateBulkFileMetadataInput(UUID.fromString(consignmentId.toString), Nil))))
    val addOrUpdateBulkFileMetadataInput = request2.variables.addOrUpdateBulkFileMetadataInput

    addOrUpdateBulkFileMetadataInput.fileMetadata.size should be(3)
    addOrUpdateBulkFileMetadataInput.fileMetadata should be(expectedFileMetadataInput(fileIdMetadata))

    updateConsignmentStatusInput.statusType must be("DraftMetadata")
    updateConsignmentStatusInput.statusValue must be(Some("Completed"))
  }

  "handleRequest" should "download the draft metadata csv file, check for schema errors and save error file with errors to s3" in {
    val fileIdMetadata = testFileIdMetadata(Seq("test/test1.txt", "test/test2.txt", "test/test3.txt"))
    authOkJson()
    graphqlOkJson(testFileIdMetadata = fileIdMetadata)
    mockS3GetResponse("invalid-sample.csv")
    checkFileError("json/error-file.json")
  }

  "handleRequest" should "download the draft metadata csv file, check for duplicate columns and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson()
    mockS3GetResponse("sample-duplicate-headers.csv")
    checkFileError("json/error-file-duplicate-headers.json")
  }

  "handleRequest" should "download the draft metadata csv file, validate the required and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson()
    mockS3GetResponse("sample-missing-required-column.csv")
    checkFileError("json/error-file-required.json")
  }

  "handleRequest" should "download the a draft metadata csv file without BOM, validate it as a UTF-8 with BOM and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson()
    mockS3GetResponse("sample-no-bom.csv")
    checkFileError("json/no-bom-error.json")
  }

  "handleRequest" should "download the a draft metadata csv file with no key value column try and load and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson()
    mockS3GetResponse("sample-no-match-column.csv")
    checkFileError("json/no-match-col-error.json")
  }

  "handleRequest" should "download the draft metadata csv file with duplicate file row errors, validate it and save error file with errors to s3" in {
    val fileIdMetadata = testFileIdMetadata(Seq("test/test1.txt", "test/test2.txt", "test/test3.txt"))
    authOkJson()
    graphqlOkJson(testFileIdMetadata = fileIdMetadata)
    mockS3GetResponse("sample-invalid-rows-duplicate.csv")
    checkFileError("json/error-file-invalid-rows-duplicate.json")
  }

  "handleRequest" should "download the draft metadata csv file with validation errors and duplicate file rows, validate it and save error file with errors to s3" in {
    val fileIdMetadata = testFileIdMetadata(Seq("test/test1.txt", "test/test2.txt", "test/test3.txt"))
    authOkJson()
    graphqlOkJson(testFileIdMetadata = fileIdMetadata)
    mockS3GetResponse("sample-validation-error-invalid-rows-duplicate.csv")
    checkFileError("json/error-file-validation-error-invalid-rows-duplicate.json")
  }

  "handleRequest" should "download the draft metadata csv file with missing file row errors, validate it and save error file with errors to s3" in {
    val fileIdMetadata = testFileIdMetadata(Seq("test/test1.txt", "test/test2.txt", "test/test3.txt"))
    authOkJson()
    graphqlOkJson(testFileIdMetadata = fileIdMetadata)
    mockS3GetResponse("sample-invalid-rows-missing.csv")
    checkFileError("json/error-file-invalid-rows-missing.json")
  }

  "handleRequest" should "download the draft metadata csv file with unknown file row errors, validate it and save error file with errors to s3" in {
    val fileIdMetadata = testFileIdMetadata(Seq("test/test1.txt", "test/test2.txt", "test/test3.txt"))
    authOkJson()
    graphqlOkJson(testFileIdMetadata = fileIdMetadata)
    mockS3GetResponse("sample-invalid-rows-unknown.csv")
    checkFileError("json/error-file-validation-error-invalid-rows-unknown.json")
  }

  "handleRequest" should "download the draft metadata csv file with duplicate unknown file row errors, validate it and save error file with errors to s3" in {
    val fileIdMetadata = testFileIdMetadata(Seq("test/test1.txt", "test/test2.txt", "test/test3.txt"))
    authOkJson()
    graphqlOkJson(testFileIdMetadata = fileIdMetadata)
    mockS3GetResponse("sample-invalid-rows-duplicate-unknown.csv")
    checkFileError("json/error-file-invalid-rows-duplicate-unknown.json")
  }

  "handleRequest" should "download the draft metadata csv with file validation and file row errors, validate it and save error file with errors to s3" in {
    val fileIdMetadata = testFileIdMetadata(Seq("test/test1.txt", "test/test2.txt", "test/test3.txt"))
    authOkJson()
    graphqlOkJson(testFileIdMetadata = fileIdMetadata)
    mockS3GetResponse("sample-validation-errors-invalid-rows.csv")
    checkFileError("json/error-file-validation-errors-invalid-rows.json")
  }

  private def checkFileError(errorFile: String) = {
    mockS3ErrorFilePutResponse()
    val input = Map("consignmentId" -> consignmentId).asJava
    val response = new Lambda().handleRequest(input, mockContext)
    response.getStatusCode should equal(200)

    val s3Interactions: Iterable[ServeEvent] = wiremockS3.getAllServeEvents.asScala.filter(serveEvent => serveEvent.getRequest.getMethod == RequestMethod.PUT).toList
    s3Interactions.size shouldBe 1

    val errorWriteRequest = s3Interactions.head
    val errorFileData = errorWriteRequest.getRequest.getBodyAsString

    val today = dateFormat.format(new Date)
    val expectedErrorData: String = Source.fromResource(errorFile).getLines.mkString(System.lineSeparator()).replace("$today", today)
    errorFileData shouldBe expectedErrorData

    val updateConsignmentStatusEvent = getServeEvent("updateConsignmentStatus").get
    val request: UpdateConsignmentStatusGraphqlRequestData = decode[UpdateConsignmentStatusGraphqlRequestData](updateConsignmentStatusEvent.getRequest.getBodyAsString)
      .getOrElse(UpdateConsignmentStatusGraphqlRequestData("", ucs.Variables(ConsignmentStatusInput(UUID.fromString(consignmentId.toString), "", None))))
    val updateConsignmentStatusInput = request.variables.updateConsignmentStatusInput

    updateConsignmentStatusInput.statusType must be("DraftMetadata")
    updateConsignmentStatusInput.statusValue must be(Some("CompletedWithIssues"))
  }

  private def expectedFileMetadataInput(fileIdMetadata: Seq[TestFileIdMetadata]): List[AddOrUpdateFileMetadata] = {
    List(
      AddOrUpdateFileMetadata(
        fileIdMetadata.find(_.clientId == "test/test3.txt").get.fileId,
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
        fileIdMetadata.find(_.clientId == "test/test1.txt").get.fileId,
        List(
          AddOrUpdateMetadata("DescriptionClosed", "false"),
          AddOrUpdateMetadata("FoiExemptionCode", "27(1)"),
          AddOrUpdateMetadata("FoiExemptionCode", "27(2)"),
          AddOrUpdateMetadata("DescriptionAlternate", ""),
          AddOrUpdateMetadata("former_reference_department", ""),
          AddOrUpdateMetadata("ClosurePeriod", "33"),
          AddOrUpdateMetadata("TitleAlternate", "title"),
          AddOrUpdateMetadata("TitleClosed", "true"),
          AddOrUpdateMetadata("file_name_translation", ""),
          AddOrUpdateMetadata("ClosureType", "Closed"),
          AddOrUpdateMetadata("description", "hello"),
          AddOrUpdateMetadata("FoiExemptionAsserted", "1990-01-01 00:00:00.0"),
          AddOrUpdateMetadata("ClosureStartDate", "1990-01-01 00:00:00.0"),
          AddOrUpdateMetadata("Language", "English"),
          AddOrUpdateMetadata("end_date", "")
        )
      ),
      AddOrUpdateFileMetadata(
        fileIdMetadata.find(_.clientId == "test/test2.txt").get.fileId,
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
case class AddOrUpdateBulkFileMetadataGraphqlRequestData(query: String, variables: afm.Variables)
