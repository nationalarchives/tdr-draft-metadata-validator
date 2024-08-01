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
    val expectedCSVHeader =
      "Filename,Filepath,Date last modified,Closure status,Closure Start Date,Closure Period,FOI exemption code,FOI decision asserted,Is the title sensitive for the public?,Add alternative title without the file extension,Description,Is the description sensitive for the public?,Alternative description,Language,Date of the record,Translated title of record,Former reference,UUID,Error"
    val expectedCSVRow1 =
      "test3.txt,test/test3.txt,12/2/2345,Closed,,,,,No,,hhhhh,No,,English,,,,a060c57d-1639-4828-9a7a-67a7c64dbf6c,date_last_modified: format.date | foi_exemption_asserted: type | foi_exemption_code: type | closure_period: type | closure_start_date: type"
    val csvLines = csvWriteEvent.getRequest.getBodyAsString.split("\\n")
    csvLines(0).strip() shouldBe expectedCSVHeader
    println(csvLines(1).strip())
    csvLines(1).strip() shouldBe expectedCSVRow1
  }
}
