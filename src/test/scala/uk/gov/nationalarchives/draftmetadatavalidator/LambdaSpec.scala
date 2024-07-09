package uk.gov.nationalarchives.draftmetadatavalidator

import com.amazonaws.services.lambda.runtime.Context
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, get, put, urlEqualTo}
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.stubbing.{ServeEvent, StubMapping}
import org.mockito.MockitoSugar.mock
import org.scalatest.matchers.should.Matchers.{convertToAnyShouldWrapper, equal}
import uk.gov.nationalarchives.draftmetadatavalidator.ApplicationConfig.{bucket, fileName, rootDirectory}

import java.nio.file.{Files, Paths}
import java.util.UUID
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
    val expectedCSVRow1 = "test3.txt,test/test3.txt,12/2/2345,Closed,,,,,No,,hhhhh,No,,English,,,,a060c57d-1639-4828-9a7a-67a7c64dbf6c,date_last_modified: format.date"
    val csvLines = csvWriteEvent.getRequest.getBodyAsString.split("\\n")
    csvLines(0).strip() shouldBe expectedCSVHeader
    csvLines(1).strip() shouldBe expectedCSVRow1
  }

  "'extractInputParameters'" should "return the inputs as a Map of strings to objects" in {
    val input = Map(
      "parameter1" -> "parameterValue1".asInstanceOf[Object],
      "parameter2" -> "parameterValue2".asInstanceOf[Object],
      "parameter3" -> "parameterValue3".asInstanceOf[Object]
    ).asJava

    val parameters = Lambda.extractInputParameters(input)

    parameters.size() shouldBe 3
    parameters.get("parameter1") shouldBe "parameterValue1"
    parameters.get("parameter2") shouldBe "parameterValue2"
    parameters.get("parameter3") shouldBe "parameterValue3"
  }

  "'DraftMetadataConfiguration'" should "set default configuration values where optional input parameters not passed" in {
    val defaultInput = Map("consignmentId" -> "f82af3bf-b742-454c-9771-bfd6c5eae749".asInstanceOf[Object]).asJava

    val defaultConfig = Lambda.DraftMetadataConfiguration(defaultInput)
    defaultConfig.consignmentId shouldBe UUID.fromString(consignmentId.toString)
    defaultConfig.folderPath shouldBe s"$rootDirectory/${consignmentId.toString}"
    defaultConfig.bucketKey shouldBe s"${consignmentId.toString}/$fileName"
    defaultConfig.sourceS3Bucket shouldBe s"$bucket"
  }

  "'DraftMetadataConfiguration'" should "set configuration values based on input parameters passed in" in {
    val input = Map(
      "consignmentId" -> "f82af3bf-b742-454c-9771-bfd6c5eae749".asInstanceOf[Object],
      "sourceS3Bucket" -> "s3BucketName".asInstanceOf[Object],
      "metadataBucketKey" -> "s3/bucket/key/filename.csv".asInstanceOf[Object],
      "persistValidData" -> "false".asInstanceOf[Object]
    ).asJava

    val config = Lambda.DraftMetadataConfiguration(input)
    config.consignmentId shouldBe UUID.fromString(consignmentId.toString)
    config.folderPath shouldBe s"$rootDirectory/s3/bucket/key"
    config.bucketKey shouldBe "s3/bucket/key/filename.csv"
    config.sourceS3Bucket shouldBe "s3BucketName"
  }

  "'DraftMetadataConfiguration'" should "ignore any unrecognised input parameters" in {
    val input = Map(
      "consignmentId" -> "f82af3bf-b742-454c-9771-bfd6c5eae749".asInstanceOf[Object],
      "unrecognizedParam" -> "someValue".asInstanceOf[Object]
    ).asJava

    val config = Lambda.DraftMetadataConfiguration(input)
    config.consignmentId shouldBe UUID.fromString(consignmentId.toString)
  }
}
