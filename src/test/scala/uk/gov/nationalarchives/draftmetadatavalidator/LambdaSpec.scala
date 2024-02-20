package uk.gov.nationalarchives.draftmetadatavalidator

import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, get, urlEqualTo}
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import org.apache.commons.io.output.ByteArrayOutputStream

import java.io.ByteArrayInputStream
import java.nio.file.{Files, Paths}

class LambdaSpec extends ExternalServicesSpec {

  val consignmentId = "f82af3bf-b742-454c-9771-bfd6c5eae749"

  def mockS3Response(): StubMapping = {
    val fileId = "sample.csv"
    val filePath = getClass.getResource("/sample.csv").getFile
    val bytes = Files.readAllBytes(Paths.get(filePath))
    wiremockS3.stubFor(
      get(urlEqualTo(s"/$consignmentId/$fileId"))
        .willReturn(aResponse().withStatus(200).withBody(bytes))
    )
  }

  def createEvent: ByteArrayInputStream = {
    val input =
      s"""{
        |  "consignmentId": "$consignmentId"
        |}""".stripMargin
    new ByteArrayInputStream(input.getBytes())
  }

  "handleRequest" should "download the draft metadata csv file, validate it and re-upload to s3 bucket if it has any errors" in {
    authOkJson()
    graphqlOkJson()
    val outputStream = new ByteArrayOutputStream()
    mockS3Response()
    new Lambda().handleRequest(createEvent, outputStream)
  }
}
