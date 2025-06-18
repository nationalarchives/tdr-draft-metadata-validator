package uk.gov.nationalarchives.draftmetadatapersistor

import com.amazonaws.services.lambda.runtime.Context
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import graphql.codegen.AddOrUpdateBulkFileMetadata.{addOrUpdateBulkFileMetadata => afm}
import graphql.codegen.UpdateConsignmentMetadataSchemaLibraryVersion.{updateConsignmentMetadataSchemaLibraryVersion => ucslv}
import graphql.codegen.types._
import io.circe.parser.decode
import org.mockito.MockitoSugar.mock
import org.scalatest.matchers.must.Matchers.{be, convertToAnyMustWrapper, include}
import org.scalatest.matchers.should.Matchers.{convertToAnyShouldWrapper, equal}
import sttp.model.StatusCode
import uk.gov.nationalarchives.draftmetadata.TestUtils
import uk.gov.nationalarchives.draftmetadata.FileTestData
import uk.gov.nationalarchives.draftmetadata.grapgql.GraphqlRequestModel._
import uk.gov.nationalarchives.draftmetadata.grapgql.{AddOrUpdateBulkFileMetadataGraphqlRequestData, UpdateConsignmentMetadataSchemaLibraryVersionGraphqlRequestData}
import uk.gov.nationalarchives.draftmetadata.{ExternalServicesSpec}
import uk.gov.nationalarchives.tdr.error.HttpException

import java.nio.file.{Files, Paths}
import java.text.SimpleDateFormat
import java.util.UUID
import scala.jdk.CollectionConverters.MapHasAsJava

class LambdaSpec extends ExternalServicesSpec {

  val consignmentId: Object = "f82af3bf-b742-454c-9771-bfd6c5eae749"
  val mockContext: Context = mock[Context]
  val pattern = "yyyy-MM-dd"
  val dateFormat = new SimpleDateFormat(pattern)

  private def saveMetadata(csvFile: String): Unit = {
    authOkJson()
    graphqlOkJson(saveMetadata = true, TestUtils.filesWithUniquesAssetIdKeyResponse(TestUtils.fileTestData))
    mockS3GetResponse(csvFile)

    val input = Map("consignmentId" -> consignmentId).asJava
    new Lambda().handleRequest(input, mockContext)

    val addOrUpdateBulkFileMetadataInput = decode[AddOrUpdateBulkFileMetadataGraphqlRequestData](
      getServeEvent("addOrUpdateBulkFileMetadata").get.getRequest.getBodyAsString
    ).getOrElse(AddOrUpdateBulkFileMetadataGraphqlRequestData("", afm.Variables(AddOrUpdateBulkFileMetadataInput(UUID.fromString(consignmentId.toString), Nil, None))))
      .variables
      .addOrUpdateBulkFileMetadataInput

    val updateConsignmentMetadataSchemaLibraryVersion = decode[UpdateConsignmentMetadataSchemaLibraryVersionGraphqlRequestData](
      getServeEvent("updateMetadataSchemaLibraryVersion").get.getRequest.getBodyAsString
    ).getOrElse(
      UpdateConsignmentMetadataSchemaLibraryVersionGraphqlRequestData(
        "",
        ucslv.Variables(UpdateMetadataSchemaLibraryVersionInput(UUID.fromString(consignmentId.toString), "failed"))
      )
    ).variables
      .updateMetadataSchemaLibraryVersionInput

    addOrUpdateBulkFileMetadataInput.fileMetadata.size should be(3)
    addOrUpdateBulkFileMetadataInput.fileMetadata should be(expectedFileMetadataInput(TestUtils.fileTestData))
    addOrUpdateBulkFileMetadataInput.skipValidation should be(Some(true))

    updateConsignmentMetadataSchemaLibraryVersion.metadataSchemaLibraryVersion mustNot be("failed")
    updateConsignmentMetadataSchemaLibraryVersion.metadataSchemaLibraryVersion mustNot be("Failed to get schema library version")
  }

  def mockS3GetResponse(fileName: String): StubMapping = {
    val filePath = getClass.getResource(s"/$fileName").getFile
    val bytes = Files.readAllBytes(Paths.get(filePath))
    wiremockS3.stubFor(
      get(urlEqualTo(s"/$consignmentId/sample.csv"))
        .willReturn(aResponse().withStatus(200).withBody(bytes))
    )
  }

  "handleRequest" should "download the draft metadata csv file, validate, save empty error file to s3 and save metadata to db if it has no errors" in {
    saveMetadata("sample.csv")
  }

  "handleRequest" should "return 500 response and throw an error message when a call to the api fails" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = TestUtils.filesWithUniquesAssetIdKeyResponse(TestUtils.fileTestData))
    mockS3GetResponse("sample.csv")

    wiremockGraphqlServer.stubFor(
      post(urlEqualTo(graphQlPath))
        .withRequestBody(containing("addOrUpdateBulkFileMetadata"))
        .willReturn(serverError().withBody("Failed to persist metadata"))
    )

    val input = Map("consignmentId" -> consignmentId).asJava
    val exception = intercept[HttpException] {
      new Lambda().handleRequest(input, mockContext)
    }

    exception.code should equal(StatusCode(500))
    exception.getMessage should include("Failed to persist metadata")
  }

  private def expectedFileMetadataInput(fileTestData: List[FileTestData]): List[AddOrUpdateFileMetadata] = {
    List(
      AddOrUpdateFileMetadata(
        fileTestData.find(_.filePath == "test/test3.txt").get.fileId,
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
        fileTestData.find(_.filePath == "test/test1.txt").get.fileId,
        List(
          AddOrUpdateMetadata("DescriptionClosed", "false"),
          AddOrUpdateMetadata("FoiExemptionCode", "27(1);27(2)"),
          AddOrUpdateMetadata("DescriptionAlternate", ""),
          AddOrUpdateMetadata("former_reference_department", ""),
          AddOrUpdateMetadata("ClosurePeriod", "33;44"),
          AddOrUpdateMetadata("TitleAlternate", "title"),
          AddOrUpdateMetadata("TitleClosed", "true"),
          AddOrUpdateMetadata("file_name_translation", ""),
          AddOrUpdateMetadata("ClosureType", "Closed"),
          AddOrUpdateMetadata("description", "hello"),
          AddOrUpdateMetadata("FoiExemptionAsserted", "1990-01-01 00:00:00.0"),
          AddOrUpdateMetadata("ClosureStartDate", "1990-01-01 00:00:00.0"),
          AddOrUpdateMetadata("Language", "English"),
          AddOrUpdateMetadata("end_date", "1990-01-01 00:00:00.0")
        )
      ),
      AddOrUpdateFileMetadata(
        fileTestData.find(_.filePath == "test/test2.txt").get.fileId,
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
