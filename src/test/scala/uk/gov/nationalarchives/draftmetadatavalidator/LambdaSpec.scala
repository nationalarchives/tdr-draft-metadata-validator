package uk.gov.nationalarchives.draftmetadatavalidator

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, get, put, urlEqualTo}
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import org.mockito.MockitoSugar.mock
import org.scalatest.matchers.should.Matchers.{convertToAnyShouldWrapper, equal}

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.MapHasAsJava

class LambdaSpec extends ExternalServicesSpec {

  val consignmentId = "f82af3bf-b742-454c-9771-bfd6c5eae749"
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

  def createEvent: APIGatewayProxyRequestEvent = {
    val pathParams = Map("consignmentId" -> consignmentId).asJava
    val event = new APIGatewayProxyRequestEvent()
    event.setPathParameters(pathParams)
    event
  }

  "handleRequest" should "download the draft metadata csv file, validate and save to db if it has no errors" in {
    authOkJson()
    graphqlOkJson(true)
    mockS3GetResponse("sample.csv")
    val pathParams = Map("consignmentId" -> consignmentId).asJava
    val event = new APIGatewayProxyRequestEvent()
    event.setPathParameters(pathParams)
    //val response = new Lambda().handleRequest(createEvent, mockContext)
    //response.getStatusCode should equal(200)
  }

  "handleRequest" should "download the draft metadata csv file, validate it and re-upload to s3 bucket if it has any errors" in {
    authOkJson()
    graphqlOkJson()
    mockS3GetResponse("invalid-sample.csv")
    mockS3PutResponse()
    val pathParams = Map("consignmentId" -> consignmentId).asJava
    val event = new APIGatewayProxyRequestEvent()
    event.setPathParameters(pathParams)
    //val response = new Lambda().handleRequest(createEvent, mockContext)
    //response.getStatusCode should equal(200)
  }
}
