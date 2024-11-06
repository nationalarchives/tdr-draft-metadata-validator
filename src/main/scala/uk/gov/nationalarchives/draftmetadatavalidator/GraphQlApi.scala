package uk.gov.nationalarchives.draftmetadatavalidator

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import com.typesafe.scalalogging.Logger
import graphql.codegen.AddOrUpdateBulkFileMetadata.addOrUpdateBulkFileMetadata.AddOrUpdateBulkFileMetadata
import graphql.codegen.AddOrUpdateBulkFileMetadata.{addOrUpdateBulkFileMetadata => afm}
import graphql.codegen.GetCustomMetadata.{customMetadata => cm}
import graphql.codegen.UpdateConsignmentStatus.{updateConsignmentStatus => ucs}
import graphql.codegen.types.{AddOrUpdateBulkFileMetadataInput, AddOrUpdateFileMetadata, ConsignmentStatusInput}
import sttp.client3._
import uk.gov.nationalarchives.draftmetadatavalidator.ApplicationConfig.clientId
import uk.gov.nationalarchives.draftmetadatavalidator.GraphQlApi.PersistedData
import uk.gov.nationalarchives.tdr.GraphQLClient
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

class GraphQlApi(
    keycloak: KeycloakUtils,
    customMetadataClient: GraphQLClient[cm.Data, cm.Variables],
    updateConsignmentStatus: GraphQLClient[ucs.Data, ucs.Variables],
    addOrUpdateBulkFileMetadata: GraphQLClient[afm.Data, afm.Variables]
)(implicit
    logger: Logger,
    keycloakDeployment: TdrKeycloakDeployment,
    backend: SttpBackend[Identity, Any]
) {
  // Temp data until retrieve data from DB
  private val mockedPersistedData = Set(
    PersistedData(UUID.fromString("cbf2cba5-f1dc-45bd-ae6d-2b042336ce6c"), "test/test1.txt"),
    PersistedData(UUID.fromString("c4d5e0f1-f6e1-4a77-a7c0-a4317404da00"), "test/test2.txt"),
    PersistedData(UUID.fromString("a060c57d-1639-4828-9a7a-67a7c64dbf6c"), "test/test3.txt")
  )

  def getCustomMetadata(consignmentId: UUID, clientSecret: String)(implicit executionContext: ExecutionContext): IO[List[cm.CustomMetadata]] = for {
    token <- keycloak.serviceAccountToken(clientId, clientSecret).toIO
    metadata <- customMetadataClient.getResult(token, cm.document, cm.Variables(consignmentId).some).toIO
    data <- IO.fromOption(metadata.data)(new RuntimeException("No custom metadata definitions found"))
  } yield data.customMetadata

  def getPersistedIdentifiers(consignmentId: UUID, clientSecret: String)(implicit executionContext: ExecutionContext): IO[List[PersistedData]] = for {
    token <- keycloak.serviceAccountToken(clientId, clientSecret).toIO
    data = mockedPersistedData
    persistedData <- IO(mockedPersistedData.toList)
  } yield persistedData

  def updateConsignmentStatus(consignmentId: UUID, clientSecret: String, statusType: String, statusValue: String)(implicit executionContext: ExecutionContext): IO[Option[Int]] =
    for {
      token <- keycloak.serviceAccountToken(clientId, clientSecret).toIO
      metadata <- updateConsignmentStatus.getResult(token, ucs.document, ucs.Variables(ConsignmentStatusInput(consignmentId, statusType, statusValue.some)).some).toIO
      data <- IO.fromOption(metadata.data)(new RuntimeException("Unable to update consignment status"))
    } yield data.updateConsignmentStatus

  def addOrUpdateBulkFileMetadata(consignmentId: UUID, clientSecret: String, fileMetadata: List[AddOrUpdateFileMetadata])(implicit
      executionContext: ExecutionContext
  ): IO[List[AddOrUpdateBulkFileMetadata]] =
    for {
      token <- keycloak.serviceAccountToken(clientId, clientSecret).toIO
      metadata <- addOrUpdateBulkFileMetadata.getResult(token, afm.document, afm.Variables(AddOrUpdateBulkFileMetadataInput(consignmentId, fileMetadata)).some).toIO
      data <- IO.fromOption(metadata.data)(new RuntimeException("Unable to add or update bulk file metadata"))
    } yield data.addOrUpdateBulkFileMetadata

  implicit class FutureUtils[T](f: Future[T]) {
    def toIO: IO[T] = IO.fromFuture(IO(f))
  }
}

object GraphQlApi {
  // Temp case class until retrieve data from DB
  case class PersistedData(fileId: UUID, filePath: String)

  def apply(
      keycloak: KeycloakUtils,
      customMetadataClient: GraphQLClient[cm.Data, cm.Variables],
      updateConsignmentStatus: GraphQLClient[ucs.Data, ucs.Variables],
      addOrUpdateBulkFileMetadata: GraphQLClient[afm.Data, afm.Variables]
  )(implicit
      backend: SttpBackend[Identity, Any],
      keycloakDeployment: TdrKeycloakDeployment
  ): GraphQlApi = {
    val logger: Logger = Logger[GraphQlApi]
    new GraphQlApi(keycloak, customMetadataClient, updateConsignmentStatus, addOrUpdateBulkFileMetadata)(logger, keycloakDeployment, backend)
  }
}
