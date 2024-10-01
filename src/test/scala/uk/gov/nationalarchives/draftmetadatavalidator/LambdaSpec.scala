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
    graphqlOkJson(true)
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
    addOrUpdateBulkFileMetadataInput.fileMetadata should be(expectedFileMetadataInput)

    updateConsignmentStatusInput.statusType must be("DraftMetadata")
    updateConsignmentStatusInput.statusValue must be(Some("Completed"))
  }

  "handleRequest" should "download the draft metadata csv file, validate it and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson()
    mockS3GetResponse("invalid-sample.csv")
    mockS3ErrorFilePutResponse()
    val input = Map("consignmentId" -> consignmentId).asJava
    val response = new Lambda().handleRequest(input, mockContext)
    response.getStatusCode should equal(200)

    val s3Interactions: Iterable[ServeEvent] = wiremockS3.getAllServeEvents.asScala.filter(serveEvent => serveEvent.getRequest.getMethod == RequestMethod.PUT).toList
    s3Interactions.size shouldBe 1

    val errorWriteRequest = s3Interactions.head
    val errorFileData = errorWriteRequest.getRequest.getBodyAsString

    val today = dateFormat.format(new Date)
    val expectedErrorData: String = Source.fromResource("json/error-file.json").getLines.mkString(System.lineSeparator()).replace("$today", today)
    errorFileData shouldBe expectedErrorData

    val updateConsignmentStatusEvent = getServeEvent("updateConsignmentStatus").get
    val request: UpdateConsignmentStatusGraphqlRequestData = decode[UpdateConsignmentStatusGraphqlRequestData](updateConsignmentStatusEvent.getRequest.getBodyAsString)
      .getOrElse(UpdateConsignmentStatusGraphqlRequestData("", ucs.Variables(ConsignmentStatusInput(UUID.fromString(consignmentId.toString), "", None))))
    val updateConsignmentStatusInput = request.variables.updateConsignmentStatusInput

    updateConsignmentStatusInput.statusType must be("DraftMetadata")
    updateConsignmentStatusInput.statusValue must be(Some("CompletedWithIssues"))
  }

  "handleRequest" should "download the a draft metadata csv file without BOM, validate it as a UTF-8 with BOM and save error file with errors to s3" in {
    authOkJson()
    graphqlOkJson()
    mockS3GetResponse("sample-no-bom.csv")
    mockS3ErrorFilePutResponse()
    val input = Map("consignmentId" -> consignmentId).asJava
    val response = new Lambda().handleRequest(input, mockContext)
    response.getStatusCode should equal(200)

    val s3Interactions: Iterable[ServeEvent] = wiremockS3.getAllServeEvents.asScala.filter(serveEvent => serveEvent.getRequest.getMethod == RequestMethod.PUT).toList
    s3Interactions.size shouldBe 1

    val errorWriteRequest = s3Interactions.head
    val errorFileData = errorWriteRequest.getRequest.getBodyAsString

    val today = dateFormat.format(new Date)
    val expectedErrorData: String = Source.fromResource("json/no-bom-error.json").getLines.mkString(System.lineSeparator()).replace("$today", today)
    errorFileData shouldBe expectedErrorData

    val updateConsignmentStatusEvent = getServeEvent("updateConsignmentStatus").get
    val request: UpdateConsignmentStatusGraphqlRequestData = decode[UpdateConsignmentStatusGraphqlRequestData](updateConsignmentStatusEvent.getRequest.getBodyAsString)
      .getOrElse(UpdateConsignmentStatusGraphqlRequestData("", ucs.Variables(ConsignmentStatusInput(UUID.fromString(consignmentId.toString), "", None))))
    val updateConsignmentStatusInput = request.variables.updateConsignmentStatusInput

    updateConsignmentStatusInput.statusType must be("DraftMetadata")
    updateConsignmentStatusInput.statusValue must be(Some("CompletedWithIssues"))
  }

  private def expectedFileMetadataInput: List[AddOrUpdateFileMetadata] = {
    List(
      AddOrUpdateFileMetadata(
        UUID.fromString("a060c57d-1639-4828-9a7a-67a7c64dbf6c"),
        List(
          AddOrUpdateMetadata("end_date", ""),
          AddOrUpdateMetadata("description", "eee"),
          AddOrUpdateMetadata("former_reference_department", ""),
          AddOrUpdateMetadata("ClosureType", "Open"),
          AddOrUpdateMetadata("ClosureStartDate", ""),
          AddOrUpdateMetadata("ClosurePeriod", ""),
          AddOrUpdateMetadata("FoiExemptionCode", ""),
          AddOrUpdateMetadata("FoiExemptionAsserted", ""),
          AddOrUpdateMetadata("TitleClosed", ""),
          AddOrUpdateMetadata("TitleAlternate", ""),
          AddOrUpdateMetadata("DescriptionClosed", ""),
          AddOrUpdateMetadata("DescriptionAlternate", ""),
          AddOrUpdateMetadata("Language", "English"),
          AddOrUpdateMetadata("file_name_translation", "")
        )
      ),
      AddOrUpdateFileMetadata(
        UUID.fromString("cbf2cba5-f1dc-45bd-ae6d-2b042336ce6c"),
        List(
          AddOrUpdateMetadata("end_date", ""),
          AddOrUpdateMetadata("description", "hello"),
          AddOrUpdateMetadata("former_reference_department", ""),
          AddOrUpdateMetadata("ClosureType", "Closed"),
          AddOrUpdateMetadata("ClosureStartDate", "1990-01-01 00:00:00.0"),
          AddOrUpdateMetadata("ClosurePeriod", "33"),
          AddOrUpdateMetadata("FoiExemptionCode", "27(1)"),
          AddOrUpdateMetadata("FoiExemptionCode", "27(2)"),
          AddOrUpdateMetadata("FoiExemptionAsserted", "1990-01-01 00:00:00.0"),
          AddOrUpdateMetadata("TitleClosed", "true"),
          AddOrUpdateMetadata("TitleAlternate", "title"),
          AddOrUpdateMetadata("DescriptionClosed", "false"),
          AddOrUpdateMetadata("DescriptionAlternate", ""),
          AddOrUpdateMetadata("Language", "English"),
          AddOrUpdateMetadata("file_name_translation", "")
        )
      ),
      AddOrUpdateFileMetadata(
        UUID.fromString("c4d5e0f1-f6e1-4a77-a7c0-a4317404da00"),
        List(
          AddOrUpdateMetadata("end_date", ""),
          AddOrUpdateMetadata("description", "www"),
          AddOrUpdateMetadata("former_reference_department", ""),
          AddOrUpdateMetadata("ClosureType", "Open"),
          AddOrUpdateMetadata("ClosureStartDate", ""),
          AddOrUpdateMetadata("ClosurePeriod", ""),
          AddOrUpdateMetadata("FoiExemptionCode", ""),
          AddOrUpdateMetadata("FoiExemptionAsserted", ""),
          AddOrUpdateMetadata("TitleClosed", ""),
          AddOrUpdateMetadata("TitleAlternate", ""),
          AddOrUpdateMetadata("DescriptionClosed", ""),
          AddOrUpdateMetadata("DescriptionAlternate", ""),
          AddOrUpdateMetadata("Language", "English"),
          AddOrUpdateMetadata("file_name_translation", "")
        )
      )
    )
  }
}

case class UpdateConsignmentStatusGraphqlRequestData(query: String, variables: ucs.Variables)
case class AddOrUpdateBulkFileMetadataGraphqlRequestData(query: String, variables: afm.Variables)
