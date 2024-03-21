package uk.gov.nationalarchives.draftmetadatavalidator

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, get, urlEqualTo}
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import org.mockito.MockitoSugar.mock

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.MapHasAsJava

class LambdaSpec extends ExternalServicesSpec {

  val consignmentId = "f82af3bf-b742-454c-9771-bfd6c5eae749"
  val mockContext: Context = mock[Context]

  def mockS3Response(): StubMapping = {
    val fileId = "sample.csv"
    val filePath = getClass.getResource("/sample.csv").getFile
    val bytes = Files.readAllBytes(Paths.get(filePath))
    wiremockS3.stubFor(
      get(urlEqualTo(s"/$consignmentId/$fileId"))
        .willReturn(aResponse().withStatus(200).withBody(bytes))
    )
  }

  def createEvent: APIGatewayProxyRequestEvent = {
    val pathParams = Map("consignmentId" -> consignmentId).asJava
    val event = new APIGatewayProxyRequestEvent()
    event.setPathParameters(pathParams)
    event
  }

  "handleRequest" should "download the draft metadata csv file, validate it and re-upload to s3 bucket if it has any errors" in {
    authOkJson()
    graphqlOkJson()
    mockS3Response()
    val pathParams = Map("consignmentId" -> consignmentId).asJava
    val event = new APIGatewayProxyRequestEvent()
    event.setPathParameters(pathParams)
    new Lambda().handleRequest(createEvent, mockContext)
  }
}
