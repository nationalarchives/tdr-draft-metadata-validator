package uk.gov.nationalarchives

import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger
import graphql.codegen.GetCustomMetadata.{customMetadata => cm}
import sttp.client3._
import uk.gov.nationalarchives.tdr.error.NotAuthorisedError
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}
import uk.gov.nationalarchives.tdr.{GraphQLClient, GraphQlResponse}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

class GraphQlApi(val keycloak: KeycloakUtils, customMetadataClient: GraphQLClient[cm.Data, cm.Variables])(implicit
    val logger: Logger,
    keycloakDeployment: TdrKeycloakDeployment,
    backend: SttpBackend[Identity, Any]
) {

  implicit class ErrorUtils[D](response: GraphQlResponse[D]) {
    val errorString: String = response.errors.map(_.message).mkString("\n")
  }

  def getCustomMetadata(consignmentId: UUID, clientSecret: String)(implicit executionContext: ExecutionContext): Future[cm.Data] = {
    val configFactory = ConfigFactory.load
    val variables = new cm.Variables(consignmentId)
    val queryResult: Future[Either[String, GraphQlResponse[cm.Data]]] = (for {
      token <- keycloak.serviceAccountToken(configFactory.getString("auth.clientId"), clientSecret)
      response <- customMetadataClient.getResult(token, cm.document, Some(variables))
    } yield Right(response)) recover (e => Left(e.getMessage))

    queryResult.flatMap {
      case Right(response) =>
        response.errors match {
          case Nil                                 => Future.successful(response.data.get)
          case List(authError: NotAuthorisedError) => Future.failed(new Exception(authError.message))
          case errors                              => Future.failed(new Exception(s"GraphQL response contained errors: ${errors.map(_.message).mkString}"))
        }
      case Left(error) => Future.failed(new Exception(error))
    }
  }
}

object GraphQlApi {
  def apply(keycloak: KeycloakUtils, customMetadataClient: GraphQLClient[cm.Data, cm.Variables])(implicit
      backend: SttpBackend[Identity, Any],
      keycloakDeployment: TdrKeycloakDeployment
  ): GraphQlApi = {
    val logger: Logger = Logger[GraphQlApi]
    new GraphQlApi(keycloak, customMetadataClient)(logger, keycloakDeployment, backend)
  }
}
