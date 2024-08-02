package uk.gov.nationalarchives.draftmetadatavalidator

import com.amazonaws.services.lambda.runtime.Context
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, get, put, urlEqualTo}
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.stubbing.{ServeEvent, StubMapping}
import org.mockito.MockitoSugar.mock
import org.scalatest.matchers.should.Matchers.{convertToAnyShouldWrapper, equal}

import java.nio.file.{Files, Paths}
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

  def mockS3PutResponse(): StubMapping = {
    wiremockS3.stubFor(
      put(urlEqualTo(s"/$consignmentId/sample.csv"))
        .willReturn(aResponse().withStatus(200))
    )
  }

  def mockS3ErrorFilePutResponse(): StubMapping = {
    wiremockS3.stubFor(
      put(urlEqualTo(s"/$consignmentId/draft-metadata-errors.json"))
        .willReturn(aResponse().withStatus(200))
    )
  }

  "handleRequest" should "download the draft metadata csv file, validate, save error file to s3 and save to metadata to db if it has no errors" in {
    authOkJson()
    graphqlOkJson(true)
    mockS3GetResponse("sample.csv")
    mockS3ErrorFilePutResponse
    val input = Map("consignmentId" -> consignmentId).asJava
    val response = new Lambda().handleRequest(input, mockContext)
    response.getStatusCode should equal(200)
  }

  "handleRequest" should "download the draft metadata csv file, validate it and save error file to s3" in {
    authOkJson()
    graphqlOkJson()
    mockS3GetResponse("invalid-sample.csv")
    // mockS3PutResponse()
    mockS3ErrorFilePutResponse
    val input = Map("consignmentId" -> consignmentId).asJava
    val response = new Lambda().handleRequest(input, mockContext)
    response.getStatusCode should equal(200)

    val s3Interactions: Iterable[ServeEvent] = wiremockS3.getAllServeEvents.asScala.filter(serveEvent => serveEvent.getRequest.getMethod == RequestMethod.PUT).toList
    s3Interactions.size shouldBe 1

    val errorWrite = s3Interactions.head
    val errorFileData = errorWrite.getRequest.getBodyAsString
    val today = org.joda.time.DateTime.now().toString("YYYY-MM-dd")
    val expectedErrorData = s"""{
                               |  "consignmentId" : "f82af3bf-b742-454c-9771-bfd6c5eae749",
                               |  "date" : "$today",
                               |  "validationErrors" : [
                               |    {
                               |      "assetId" : "a060c57d-1639-4828-9a7a-67a7c64dbf6c",
                               |      "errors" : [
                               |        {
                               |          "validationProcess" : "SCHEMA_CLOSURE",
                               |          "property" : "closure_period",
                               |          "errorKey" : "type"
                               |        },
                               |        {
                               |          "validationProcess" : "SCHEMA_CLOSURE",
                               |          "property" : "closure_start_date",
                               |          "errorKey" : "type"
                               |        },
                               |        {
                               |          "validationProcess" : "SCHEMA_BASE",
                               |          "property" : "date_last_modified",
                               |          "errorKey" : "format.date"
                               |        },
                               |        {
                               |          "validationProcess" : "SCHEMA_CLOSURE",
                               |          "property" : "foi_exemption_code",
                               |          "errorKey" : "type"
                               |        },
                               |        {
                               |          "validationProcess" : "SCHEMA_CLOSURE",
                               |          "property" : "foi_exemption_asserted",
                               |          "errorKey" : "type"
                               |        }
                               |      ]
                               |    },
                               |    {
                               |      "assetId" : "cbf2cba5-f1dc-45bd-ae6d-2b042336ce6c",
                               |      "errors" : [
                               |        {
                               |          "validationProcess" : "SCHEMA_BASE",
                               |          "property" : "foi_exemption_code",
                               |          "errorKey" : "enum"
                               |        },
                               |        {
                               |          "validationProcess" : "SCHEMA_CLOSURE",
                               |          "property" : "foi_exemption_code",
                               |          "errorKey" : "enum"
                               |        }
                               |      ]
                               |    }
                               |  ]
                               |}""".stripMargin
    errorFileData shouldBe expectedErrorData
  }
}
