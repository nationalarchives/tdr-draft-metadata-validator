package uk.gov.nationalarchives.draftmetadatavalidator

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.{
  aResponse,
  anyUrl,
  findAll,
  get,
  getAllServeEvents,
  lessThan,
  postRequestedFor,
  put,
  urlEqualTo,
  urlMatching,
  urlPathEqualTo,
  verify
}
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.matching.{RequestPattern, RequestPatternBuilder, StringValuePattern, UrlPathPattern, UrlPathTemplatePattern, UrlPattern}
import com.github.tomakehurst.wiremock.stubbing.{ServeEvent, StubMapping}
import org.mockito.MockitoSugar.mock
import org.scalatest.matchers.must.Matchers.{contain, include}
import org.scalatest.matchers.should.Matchers.{convertToAnyShouldWrapper, equal}
import sttp.model.Method.PUT

import java.nio.file.{Files, Paths}
import java.util
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

  "handleRequest" should "download the draft metadata csv file, validate and save to db if it has no errors" in {
    authOkJson()
    graphqlOkJson(true)
    mockS3GetResponse("sample.csv")
    val input = Map("consignmentId" -> consignmentId).asJava
    val response = new Lambda().handleRequest(input, mockContext)
    response.getStatusCode should equal(200)
  }

  "handleRequest" should "download the draft metadata csv file, validate it and re-upload to s3 bucket if it has any errors" in {
    authOkJson()
    graphqlOkJson()
    mockS3GetResponse("invalid-sample.csv")
    mockS3PutResponse()
    val input = Map("consignmentId" -> consignmentId).asJava
    val response = new Lambda().handleRequest(input, mockContext)
    response.getStatusCode should equal(200)

    val s3Interactions: Iterable[ServeEvent] = wiremockS3.getAllServeEvents.asScala.filter(serveEvent => serveEvent.getRequest.getMethod == RequestMethod.PUT).toList
    s3Interactions.size shouldBe 1
    val csvWriteEvent = s3Interactions.head
    val csvLines = csvWriteEvent.getRequest.getBodyAsString.split("\\n")
    csvLines(0) should include("Error")
    csvLines(1) should include("date_last_modified: format.date")
  }
}
