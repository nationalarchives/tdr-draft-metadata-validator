package uk.gov.nationalarchives.draftmetadatavalidator

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import com.typesafe.scalalogging.Logger
import graphql.codegen.AddOrUpdateBulkFileMetadata.addOrUpdateBulkFileMetadata.AddOrUpdateBulkFileMetadata
import graphql.codegen.AddOrUpdateBulkFileMetadata.{addOrUpdateBulkFileMetadata => afm}
import graphql.codegen.GetConsignmentFilesMetadata.{getConsignmentFilesMetadata, getConsignmentFilesMetadata => gcfm}
import graphql.codegen.GetCustomMetadata.{customMetadata => cm}
import graphql.codegen.UpdateConsignmentStatus.{updateConsignmentStatus => ucs}
import graphql.codegen.types.{AddOrUpdateBulkFileMetadataInput, AddOrUpdateFileMetadata, ConsignmentStatusInput, FileFilters, FileMetadataFilters}
import sttp.client3._
import uk.gov.nationalarchives.draftmetadatavalidator.ApplicationConfig.clientId
import uk.gov.nationalarchives.tdr.{GraphQLClient, GraphQlResponse}
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

class GraphQlApi(
    keycloak: KeycloakUtils,
    customMetadataClient: GraphQLClient[cm.Data, cm.Variables],
    updateConsignmentStatus: GraphQLClient[ucs.Data, ucs.Variables],
    addOrUpdateBulkFileMetadata: GraphQLClient[afm.Data, afm.Variables],
    getConsignmentFileMetadata: GraphQLClient[gcfm.Data, gcfm.Variables]
)(implicit
    logger: Logger,
    keycloakDeployment: TdrKeycloakDeployment,
    backend: SttpBackend[Identity, Any]
) {

  def getCustomMetadata(consignmentId: UUID, clientSecret: String)(implicit executionContext: ExecutionContext): IO[List[cm.CustomMetadata]] = for {
    token <- keycloak.serviceAccountToken(clientId, clientSecret).toIO
    metadata <- customMetadataClient.getResult(token, cm.document, cm.Variables(consignmentId).some).toIO
    data <- IO.fromOption(metadata.data)(new RuntimeException("No custom metadata definitions found"))
  } yield data.customMetadata

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

  def getConsignmentFilesMetadata(consignmentId: UUID, clientSecret: String, databaseMetadataHeaders: List[String]): IO[Option[gcfm.Data]] = {
    for {
      token <- keycloak.serviceAccountToken(clientId, clientSecret).toIO
      metadata <- getConsignmentFileMetadata.getResult(
        token, 
        gcfm.document, 
        gcfm.Variables(
          consignmentId = consignmentId, 
          fileFiltersInput = FileFilters(
            fileTypeIdentifier = None, 
            selectedFileIds = None, 
            parentId = None, 
            metadataFilters = FileMetadataFilters(
              None,
              None,
              properties = Option.when(databaseMetadataHeaders.nonEmpty)(databaseMetadataHeaders)
            ).some
          ).some
        ).some
      ).toIO
    } yield metadata.data
  }
  
  implicit class FutureUtils[T](f: Future[T]) {
    def toIO: IO[T] = IO.fromFuture(IO(f))
  }
}

object GraphQlApi {
  def apply(
      keycloak: KeycloakUtils,
      customMetadataClient: GraphQLClient[cm.Data, cm.Variables],
      updateConsignmentStatus: GraphQLClient[ucs.Data, ucs.Variables],
      addOrUpdateBulkFileMetadata: GraphQLClient[afm.Data, afm.Variables], 
      getConsignmentFilesMetadata: GraphQLClient[gcfm.Data, gcfm.Variables]
  )(implicit
      backend: SttpBackend[Identity, Any],
      keycloakDeployment: TdrKeycloakDeployment
  ): GraphQlApi = {
    val logger: Logger = Logger[GraphQlApi]
    new GraphQlApi(keycloak, customMetadataClient, updateConsignmentStatus, addOrUpdateBulkFileMetadata, getConsignmentFilesMetadata)(logger, keycloakDeployment, backend)
  }
}
