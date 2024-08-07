package uk.gov.nationalarchives.draftmetadatavalidator

import cats.effect.unsafe.implicits.global
import com.nimbusds.oauth2.sdk.token.BearerAccessToken
import com.typesafe.scalalogging.Logger
import graphql.codegen.AddOrUpdateBulkFileMetadata.addOrUpdateBulkFileMetadata.AddOrUpdateBulkFileMetadata
import graphql.codegen.AddOrUpdateBulkFileMetadata.{addOrUpdateBulkFileMetadata => afm}
import graphql.codegen.GetCustomMetadata.customMetadata.CustomMetadata
import graphql.codegen.GetCustomMetadata.{customMetadata => cm}
import graphql.codegen.GetDisplayProperties.{displayProperties => dp}
import graphql.codegen.UpdateConsignmentStatus.{updateConsignmentStatus => ucs}
import graphql.codegen.types.DataType.{Boolean, Text}
import graphql.codegen.types.{AddOrUpdateFileMetadata, AddOrUpdateMetadata, DataType}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sangria.ast.Document
import sttp.client3.{HttpURLConnectionBackend, Identity, SttpBackend}
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}
import uk.gov.nationalarchives.tdr.{GraphQLClient, GraphQlResponse}

import java.util.UUID
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.reflect.ClassTag

class GraphQlApiSpec extends AnyFlatSpec with MockitoSugar with Matchers with EitherValues {

  implicit val executionContext: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()
  implicit val logger: Logger = Logger[GraphQlApi]

  implicit val tdrKeycloakDeployment: TdrKeycloakDeployment = TdrKeycloakDeployment("authUrl", "realm", 60)

  val consignmentId = UUID.randomUUID()
  val customMetadataClient: GraphQLClient[cm.Data, cm.Variables] = mock[GraphQLClient[cm.Data, cm.Variables]]
  val displayPropertiesClient: GraphQLClient[dp.Data, dp.Variables] = mock[GraphQLClient[dp.Data, dp.Variables]]
  val updateConsignmentStatusClient = mock[GraphQLClient[ucs.Data, ucs.Variables]]
  val addOrUpdateBulkFileMetadataClient = mock[GraphQLClient[afm.Data, afm.Variables]]
  val keycloak = mock[KeycloakUtils]

  val customMetadata: List[CustomMetadata] = List(
    TestUtils.createCustomMetadata("ClosureType", "Closure status", 1, DataType.Text),
    TestUtils.createCustomMetadata("ClosurePeriod", "Closure period", 2, DataType.Integer)
  )
  val displayProperties: List[dp.DisplayProperties] = List(
    dp.DisplayProperties(
      "activeProperty",
      List(
        dp.DisplayProperties.Attributes("Active", Some("true"), Boolean),
        dp.DisplayProperties.Attributes("ComponentType", Some("componentType"), Text),
        dp.DisplayProperties.Attributes("Description", Some("description value"), Text)
      )
    )
  )

  "getCustomMetadata" should "throw an exception when no custom metadata are found" in {
    val api = new GraphQlApi(keycloak, customMetadataClient, updateConsignmentStatusClient, addOrUpdateBulkFileMetadataClient, displayPropertiesClient)

    doAnswer(() => Future(new BearerAccessToken("token")))
      .when(keycloak)
      .serviceAccountToken[Identity](any[String], any[String])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]], any[TdrKeycloakDeployment])

    doAnswer(() => Future(GraphQlResponse[cm.Data](None, Nil)))
      .when(customMetadataClient)
      .getResult[Identity](any[BearerAccessToken], any[Document], any[Option[cm.Variables]])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]])

    val exception = intercept[RuntimeException] {
      api.getCustomMetadata(consignmentId, "secret").unsafeRunSync()
    }
    exception.getMessage should equal(s"No custom metadata definitions found")
  }

  "getCustomMetadata" should "return the custom metadata" in {
    val api = new GraphQlApi(keycloak, customMetadataClient, updateConsignmentStatusClient, addOrUpdateBulkFileMetadataClient, displayPropertiesClient)

    doAnswer(() => Future(new BearerAccessToken("token")))
      .when(keycloak)
      .serviceAccountToken[Identity](any[String], any[String])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]], any[TdrKeycloakDeployment])

    doAnswer(() => Future(GraphQlResponse[cm.Data](Option(cm.Data(customMetadata)), Nil)))
      .when(customMetadataClient)
      .getResult[Identity](any[BearerAccessToken], any[Document], any[Option[cm.Variables]])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]])

    val response = api.getCustomMetadata(consignmentId, "secret").unsafeRunSync()

    response should equal(customMetadata)
  }

  "getDisplayProperties" should "throw an exception when no custom metadata are found" in {
    val api = new GraphQlApi(keycloak, customMetadataClient, updateConsignmentStatusClient, addOrUpdateBulkFileMetadataClient, displayPropertiesClient)

    doAnswer(() => Future(new BearerAccessToken("token")))
      .when(keycloak)
      .serviceAccountToken[Identity](any[String], any[String])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]], any[TdrKeycloakDeployment])

    doAnswer(() => Future(GraphQlResponse[dp.Data](None, Nil)))
      .when(displayPropertiesClient)
      .getResult[Identity](any[BearerAccessToken], any[Document], any[Option[dp.Variables]])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]])

    val exception = intercept[RuntimeException] {
      api.getDisplayProperties(consignmentId, "secret").unsafeRunSync()
    }
    exception.getMessage should equal(s"No display properties definitions found")
  }

  "getDisplayProperties" should "return the custom metadata" in {

    val consignmentId = UUID.randomUUID()
    val api = new GraphQlApi(keycloak, customMetadataClient, updateConsignmentStatusClient, addOrUpdateBulkFileMetadataClient, displayPropertiesClient)

    doAnswer(() => Future(new BearerAccessToken("token")))
      .when(keycloak)
      .serviceAccountToken[Identity](any[String], any[String])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]], any[TdrKeycloakDeployment])

    doAnswer(() => Future(GraphQlResponse[dp.Data](Option(dp.Data(displayProperties)), Nil)))
      .when(displayPropertiesClient)
      .getResult[Identity](any[BearerAccessToken], any[Document], any[Option[dp.Variables]])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]])

    val response = api.getDisplayProperties(consignmentId, "secret").unsafeRunSync()

    response should equal(displayProperties)
  }

  "updateConsignmentStatus" should "throw an exception when the api fails to update the consignment status" in {
    val api = new GraphQlApi(keycloak, customMetadataClient, updateConsignmentStatusClient, addOrUpdateBulkFileMetadataClient, displayPropertiesClient)

    doAnswer(() => Future(new BearerAccessToken("token")))
      .when(keycloak)
      .serviceAccountToken[Identity](any[String], any[String])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]], any[TdrKeycloakDeployment])

    doAnswer(() => Future(GraphQlResponse[ucs.Data](None, Nil)))
      .when(updateConsignmentStatusClient)
      .getResult[Identity](any[BearerAccessToken], any[Document], any[Option[ucs.Variables]])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]])

    val exception = intercept[RuntimeException] {
      api.updateConsignmentStatus(consignmentId, "secret", "status", "value").unsafeRunSync()
    }
    exception.getMessage should equal(s"Unable to update consignment status")
  }

  "updateConsignmentStatus" should "update the consignment status with status type and value" in {

    val consignmentId = UUID.randomUUID()
    val api = new GraphQlApi(keycloak, customMetadataClient, updateConsignmentStatusClient, addOrUpdateBulkFileMetadataClient, displayPropertiesClient)

    doAnswer(() => Future(new BearerAccessToken("token")))
      .when(keycloak)
      .serviceAccountToken[Identity](any[String], any[String])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]], any[TdrKeycloakDeployment])

    doAnswer(() => Future(GraphQlResponse[ucs.Data](Option(ucs.Data(Some(1))), Nil)))
      .when(updateConsignmentStatusClient)
      .getResult[Identity](any[BearerAccessToken], any[Document], any[Option[ucs.Variables]])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]])

    val response = api.updateConsignmentStatus(consignmentId, "secret", "status", "value").unsafeRunSync()

    response should equal(Some(1))
  }

  "addOrUpdateBulkFileMetadata" should "throw an exception when the api fails to add or update the file metadata" in {
    val api = new GraphQlApi(keycloak, customMetadataClient, updateConsignmentStatusClient, addOrUpdateBulkFileMetadataClient, displayPropertiesClient)

    doAnswer(() => Future(new BearerAccessToken("token")))
      .when(keycloak)
      .serviceAccountToken[Identity](any[String], any[String])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]], any[TdrKeycloakDeployment])

    doAnswer(() => Future(GraphQlResponse[ucs.Data](None, Nil)))
      .when(addOrUpdateBulkFileMetadataClient)
      .getResult[Identity](any[BearerAccessToken], any[Document], any[Option[afm.Variables]])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]])

    val fileMetadata = List(AddOrUpdateFileMetadata(UUID.randomUUID(), List(AddOrUpdateMetadata("ClosureStatus", "Open"))))
    val exception = intercept[RuntimeException] {
      api.addOrUpdateBulkFileMetadata(consignmentId, "secret", fileMetadata).unsafeRunSync()
    }
    exception.getMessage should equal("Unable to add or update bulk file metadata")
  }

  "addOrUpdateBulkFileMetadata" should "add or update the file metadata" in {

    val consignmentId = UUID.randomUUID()
    val fileId = UUID.randomUUID()
    val api = new GraphQlApi(keycloak, customMetadataClient, updateConsignmentStatusClient, addOrUpdateBulkFileMetadataClient, displayPropertiesClient)

    doAnswer(() => Future(new BearerAccessToken("token")))
      .when(keycloak)
      .serviceAccountToken[Identity](any[String], any[String])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]], any[TdrKeycloakDeployment])

    val expected = List(AddOrUpdateBulkFileMetadata("ClosureStatus", fileId, "Open"))
    doAnswer(() => { Future(GraphQlResponse[afm.Data](Option(afm.Data(expected)), Nil)) })
      .when(addOrUpdateBulkFileMetadataClient)
      .getResult[Identity](any[BearerAccessToken], any[Document], any[Option[afm.Variables]])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]])

    val fileMetadata = List(AddOrUpdateFileMetadata(UUID.randomUUID(), List(AddOrUpdateMetadata("ClosureStatus", "Open"))))

    val response = api.addOrUpdateBulkFileMetadata(consignmentId, "secret", fileMetadata).unsafeRunSync()

    response should equal(expected)
  }
}
