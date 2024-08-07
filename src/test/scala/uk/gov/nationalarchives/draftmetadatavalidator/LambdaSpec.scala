package uk.gov.nationalarchives.draftmetadatavalidator

import com.amazonaws.services.lambda.runtime.Context
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, get, put, urlEqualTo}
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.stubbing.{ServeEvent, StubMapping}
import org.mockito.MockitoSugar.mock
import org.scalatest.matchers.should.Matchers.{convertToAnyShouldWrapper, equal}

import java.nio.file.{Files, Paths}
import java.text.SimpleDateFormat
import java.util.Date
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
  }
}
