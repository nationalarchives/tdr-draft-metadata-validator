package uk.gov.nationalarchives.tdr.draftmetadatachecks

import cats.effect.unsafe.implicits.global
import cats.implicits.catsSyntaxOptionId
import com.nimbusds.oauth2.sdk.token.BearerAccessToken
import com.typesafe.scalalogging.Logger
import graphql.codegen.GetFilesWithUniqueAssetIdKey.getFilesWithUniqueAssetIdKey.GetConsignment
import graphql.codegen.GetFilesWithUniqueAssetIdKey.{getFilesWithUniqueAssetIdKey => uaik}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sangria.ast.Document
import sttp.client3.{HttpURLConnectionBackend, Identity, SttpBackend}
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}
import uk.gov.nationalarchives.tdr.{GraphQLClient, GraphQlResponse}

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.reflect.ClassTag

class GraphQlApiSpec extends AnyFlatSpec with MockitoSugar with Matchers with EitherValues {

  implicit val executionContext: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()
  implicit val logger: Logger = Logger[GraphQlApi]

  implicit val tdrKeycloakDeployment: TdrKeycloakDeployment = TdrKeycloakDeployment("authUrl", "realm", 60)
  private val consignmentId = UUID.randomUUID()

  private val keycloak = mock[KeycloakUtils]
  private val getFilesUniquesAssetIdKey = mock[GraphQLClient[uaik.Data, uaik.Variables]]

  "getFilesWithUniqueAssetIdKey" should "return the files with unique asset id" in {
    val api = getGraphQLAPI
    val files = GetConsignment.Files(UUID.randomUUID(), "name".some, GetConsignment.Files.Metadata("text/file.txt".some, LocalDateTime.now().some))
    doAnswer(() => Future(new BearerAccessToken("token")))
      .when(keycloak)
      .serviceAccountToken[Identity](any[String], any[String])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]], any[TdrKeycloakDeployment])

    doAnswer(() => Future(GraphQlResponse[uaik.Data](Option(uaik.Data(GetConsignment(List(files)).some)), Nil)))
      .when(getFilesUniquesAssetIdKey)
      .getResult[Identity](any[BearerAccessToken], any[Document], any[Option[uaik.Variables]])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]])

    val response = api.getFilesWithUniqueAssetIdKey(consignmentId, "secret").unsafeRunSync()

    val expectedResponse = Map(
      files.metadata.clientSideOriginalFilePath.get -> FileDetail(files.fileId, files.fileName, files.metadata.clientSideLastModifiedDate)
    )
    response should equal(expectedResponse)
  }

  "getFilesWithUniqueAssetIdKey" should "throw an exception when no consignments are found" in {
    val api = getGraphQLAPI
    doAnswer(() => Future(new BearerAccessToken("token")))
      .when(keycloak)
      .serviceAccountToken[Identity](any[String], any[String])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]], any[TdrKeycloakDeployment])

    doAnswer(() => Future(GraphQlResponse[uaik.Data](Option(uaik.Data(None)), Nil)))
      .when(getFilesUniquesAssetIdKey)
      .getResult[Identity](any[BearerAccessToken], any[Document], any[Option[uaik.Variables]])(any[SttpBackend[Identity, Any]], any[ClassTag[Identity[_]]])

    val exception = intercept[RuntimeException] {
      api.getFilesWithUniqueAssetIdKey(consignmentId, "secret").unsafeRunSync()
    }
    exception.getMessage should equal("Unable to get FilesWithUniqueAssetIdKey")
  }

  private def getGraphQLAPI = {
    new GraphQlApi(
      keycloak,
      getFilesUniquesAssetIdKey
    )
  }
}
