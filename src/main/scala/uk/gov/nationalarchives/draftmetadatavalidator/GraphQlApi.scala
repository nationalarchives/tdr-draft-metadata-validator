package uk.gov.nationalarchives.draftmetadatavalidator

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import com.typesafe.scalalogging.Logger
import graphql.codegen.GetCustomMetadata.{customMetadata => cm}
import graphql.codegen.GetDisplayProperties.{displayProperties => dp}
import sttp.client3._
import uk.gov.nationalarchives.draftmetadatavalidator.ApplicationConfig.clientId
import uk.gov.nationalarchives.tdr.GraphQLClient
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

class GraphQlApi(keycloak: KeycloakUtils, customMetadataClient: GraphQLClient[cm.Data, cm.Variables], displayPropertiesClient: GraphQLClient[dp.Data, dp.Variables])(implicit
    logger: Logger,
    keycloakDeployment: TdrKeycloakDeployment,
    backend: SttpBackend[Identity, Any]
) {

  def getCustomMetadata(consignmentId: UUID, clientSecret: String)(implicit executionContext: ExecutionContext): IO[List[cm.CustomMetadata]] = for {
    token <- keycloak.serviceAccountToken(clientId, clientSecret).toIO
    metadata <- customMetadataClient.getResult(token, cm.document, cm.Variables(consignmentId).some).toIO
    data <- IO.fromOption(metadata.data)(new RuntimeException("No custom metadata definitions found"))
  } yield data.customMetadata

  def getDisplayProperties(consignmentId: UUID, clientSecret: String)(implicit executionContext: ExecutionContext): IO[List[dp.DisplayProperties]] = for {
    token <- keycloak.serviceAccountToken(clientId, clientSecret).toIO
    metadata <- displayPropertiesClient.getResult(token, dp.document, dp.Variables(consignmentId).some).toIO
    data <- IO.fromOption(metadata.data)(new RuntimeException("No display properties definitions found"))
  } yield data.displayProperties

  implicit class FutureUtils[T](f: Future[T]) {
    def toIO: IO[T] = IO.fromFuture(IO(f))
  }
}

object GraphQlApi {
  def apply(keycloak: KeycloakUtils, customMetadataClient: GraphQLClient[cm.Data, cm.Variables], displayPropertiesClient: GraphQLClient[dp.Data, dp.Variables])(implicit
      backend: SttpBackend[Identity, Any],
      keycloakDeployment: TdrKeycloakDeployment
  ): GraphQlApi = {
    val logger: Logger = Logger[GraphQlApi]
    new GraphQlApi(keycloak, customMetadataClient, displayPropertiesClient)(logger, keycloakDeployment, backend)
  }
}
