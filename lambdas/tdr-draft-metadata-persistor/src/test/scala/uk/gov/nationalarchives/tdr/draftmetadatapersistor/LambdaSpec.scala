package uk.gov.nationalarchives.tdr.draftmetadatapersistor

import com.amazonaws.services.lambda.runtime.Context
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import graphql.codegen.AddOrUpdateBulkFileMetadata.{addOrUpdateBulkFileMetadata => afm}
import graphql.codegen.UpdateConsignmentMetadataSchemaLibraryVersion.{updateConsignmentMetadataSchemaLibraryVersion => ucslv}
import graphql.codegen.types._
import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser.decode
import org.mockito.MockitoSugar.mock
import org.scalatest.matchers.must.Matchers.{be, convertToAnyMustWrapper, include}
import org.scalatest.matchers.should.Matchers.{convertToAnyShouldWrapper, equal}
import uk.gov.nationalarchives.draftmetadata.{ExternalServicesSpec, FileTestData, TestUtils}
import uk.gov.nationalarchives.tdr.draftmetadatapersistor.grapgql.GraphqlRequestModel._

import java.nio.file.{Files, Paths}
import java.text.SimpleDateFormat
import java.util.UUID
import scala.jdk.CollectionConverters.MapHasAsJava

class LambdaSpec extends ExternalServicesSpec {

  val consignmentId: Object = "f82af3bf-b742-454c-9771-bfd6c5eae749"
  val mockContext: Context = mock[Context]
  val pattern = "yyyy-MM-dd"
  val dateFormat = new SimpleDateFormat(pattern)

  "handleRequest" should "download the draft metadata csv file and save metadata to db" in {
    authOkJson()
    graphqlOkJson(saveMetadata = true, TestUtils.filesWithUniquesAssetIdKeyResponse(TestUtils.fileTestData))
    mockS3GetResponse("sample.csv")

    val input = Map("consignmentId" -> consignmentId).asJava
    new Lambda().handleRequest(input, mockContext)

    val addOrUpdateBulkFileMetadataInput = decode[AddOrUpdateBulkFileMetadataGraphqlRequestData](
      getServeEvent("addOrUpdateBulkFileMetadata").get.getRequest.getBodyAsString
    ).getOrElse(AddOrUpdateBulkFileMetadataGraphqlRequestData("", afm.Variables(AddOrUpdateBulkFileMetadataInput(UUID.fromString(consignmentId.toString), Nil))))
      .variables
      .addOrUpdateBulkFileMetadataInput

    addOrUpdateBulkFileMetadataInput.fileMetadata.size should be(3)
    addOrUpdateBulkFileMetadataInput.fileMetadata should be(expectedFileMetadataInput(TestUtils.fileTestData))
  }

  "handleRequest" should "download the draft metadata csv file and save metadata library version" in {
    authOkJson()
    graphqlOkJson(saveMetadata = true, TestUtils.filesWithUniquesAssetIdKeyResponse(TestUtils.fileTestData))
    mockS3GetResponse("sample.csv")
    val metadataSchemaLibraryVersion = "1.0.0"

    val input = Map("consignmentId" -> consignmentId, "metadataSchemaLibraryVersion" -> metadataSchemaLibraryVersion).asJava
    val result = new Lambda().handleRequest(input, mockContext)

    val updateConsignmentMetadataSchemaLibraryVersion = decode[UpdateConsignmentMetadataSchemaLibraryVersionGraphqlRequestData](
      getServeEvent("updateMetadataSchemaLibraryVersion").get.getRequest.getBodyAsString
    ).getOrElse(
      UpdateConsignmentMetadataSchemaLibraryVersionGraphqlRequestData(
        "",
        ucslv.Variables(UpdateMetadataSchemaLibraryVersionInput(UUID.fromString(consignmentId.toString), "failed"))
      )
    ).variables
      .updateMetadataSchemaLibraryVersionInput

    updateConsignmentMetadataSchemaLibraryVersion.consignmentId.toString must be(consignmentId)
    updateConsignmentMetadataSchemaLibraryVersion.metadataSchemaLibraryVersion must be(metadataSchemaLibraryVersion)
    result.get("persistenceStatus") should equal("success")
    result.get("consignmentId") should equal(consignmentId.toString)
    result.get("error") should equal("")
  }

  "handleRequest" should "download the draft metadata csv file and save metadata library version with Not provided is not provided in request" in {
    authOkJson()
    graphqlOkJson(saveMetadata = true, TestUtils.filesWithUniquesAssetIdKeyResponse(TestUtils.fileTestData))
    mockS3GetResponse("sample.csv")

    val input = Map("consignmentId" -> consignmentId).asJava
    val result = new Lambda().handleRequest(input, mockContext)

    val updateConsignmentMetadataSchemaLibraryVersion = decode[UpdateConsignmentMetadataSchemaLibraryVersionGraphqlRequestData](
      getServeEvent("updateMetadataSchemaLibraryVersion").get.getRequest.getBodyAsString
    ).getOrElse(
      UpdateConsignmentMetadataSchemaLibraryVersionGraphqlRequestData(
        "",
        ucslv.Variables(UpdateMetadataSchemaLibraryVersionInput(UUID.fromString(consignmentId.toString), "failed"))
      )
    ).variables
      .updateMetadataSchemaLibraryVersionInput

    updateConsignmentMetadataSchemaLibraryVersion.consignmentId.toString must be(consignmentId)
    updateConsignmentMetadataSchemaLibraryVersion.metadataSchemaLibraryVersion must be("Not provided")

    result.get("persistenceStatus") should equal("success")

  }

  "handleRequest" should "return data showing persistenceStatus failure when a call to the api fails" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = TestUtils.filesWithUniquesAssetIdKeyResponse(TestUtils.fileTestData))
    mockS3GetResponse("sample.csv")

    wiremockGraphqlServer.stubFor(
      post(urlEqualTo(graphQlPath))
        .withRequestBody(containing("addOrUpdateBulkFileMetadata"))
        .willReturn(serverError().withBody("Failed to persist metadata"))
    )

    val input = Map("consignmentId" -> consignmentId).asJava
    val result = new Lambda().handleRequest(input, mockContext)

    result.get("persistenceStatus") should equal("failure")
    result.get("consignmentId") should equal(consignmentId.toString)
    result.get("error").toString should include("Failed to persist metadata")
  }

  "handleRequest" should "return data showing persistenceStatus failure when a call the consignmentId is not valid" in {
    authOkJson()
    graphqlOkJson(filesWithUniquesAssetIdKeyResponse = TestUtils.filesWithUniquesAssetIdKeyResponse(TestUtils.fileTestData))
    mockS3GetResponse("sample.csv")

    val input = Map("consignmentId" -> "123".asInstanceOf[Object]).asJava
    val result = new Lambda().handleRequest(input, mockContext)

    result.get("persistenceStatus") should equal("failure")
    result.get("consignmentId") should equal("123")
    result.get("error").toString should include("Invalid UUID string: 123")
  }

  def mockS3GetResponse(fileName: String): StubMapping = {
    val filePath = getClass.getResource(s"/$fileName").getFile
    val bytes = Files.readAllBytes(Paths.get(filePath))
    wiremockS3.stubFor(
      get(urlEqualTo(s"/$consignmentId/sample.csv"))
        .willReturn(aResponse().withStatus(200).withBody(bytes))
    )
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

  case class AddOrUpdateBulkFileMetadataGraphqlRequestData(query: String, variables: afm.Variables)
  case class UpdateConsignmentMetadataSchemaLibraryVersionGraphqlRequestData(query: String, variables: ucslv.Variables)
  // Top-level decoders for the request data classes
  implicit val addOrUpdateBulkFileMetadataGraphqlRequestDataDecoder: Decoder[AddOrUpdateBulkFileMetadataGraphqlRequestData] = deriveDecoder
  implicit val updateConsignmentMetadataSchemaLibraryVersionGraphqlRequestDataDecoder: Decoder[UpdateConsignmentMetadataSchemaLibraryVersionGraphqlRequestData] = deriveDecoder
}
