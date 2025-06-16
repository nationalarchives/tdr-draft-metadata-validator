package uk.gov.nationalarchives.draftmetadatavalidator

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import com.typesafe.scalalogging.Logger
import graphql.codegen.AddOrUpdateBulkFileMetadata.addOrUpdateBulkFileMetadata.AddOrUpdateBulkFileMetadata
import graphql.codegen.AddOrUpdateBulkFileMetadata.{addOrUpdateBulkFileMetadata => afm}
import graphql.codegen.GetCustomMetadata.{customMetadata => cm}
import graphql.codegen.GetFilesWithUniqueAssetIdKey.{getFilesWithUniqueAssetIdKey => uaik}
import graphql.codegen.UpdateConsignmentMetadataSchemaLibraryVersion.{updateConsignmentMetadataSchemaLibraryVersion => ucslv}
import graphql.codegen.UpdateConsignmentStatus.{updateConsignmentStatus => ucs}
import graphql.codegen.types._
import sttp.client3._
import uk.gov.nationalarchives.draftmetadatavalidator.ApplicationConfig.{clientId, graphqlApiRequestTimeOut}
import uk.gov.nationalarchives.draftmetadatavalidator.utils.MetadataUtils.dateTimeFormatter
import uk.gov.nationalarchives.tdr.GraphQLClient
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.Future

class GraphQlApi(
    keycloak: KeycloakUtils,
    customMetadataClient: GraphQLClient[cm.Data, cm.Variables],
    updateConsignmentStatus: GraphQLClient[ucs.Data, ucs.Variables],
    addOrUpdateBulkFileMetadata: GraphQLClient[afm.Data, afm.Variables],
    getFilesWithUniqueAssetIdKey: GraphQLClient[uaik.Data, uaik.Variables],
    updateConsignmentMetadataSchemaLibraryVersion: GraphQLClient[ucslv.Data, ucslv.Variables]
)(implicit
    logger: Logger,
    keycloakDeployment: TdrKeycloakDeployment,
    backend: SttpBackend[Identity, Any]
) {

  private val fileTypeIdentifier: String = "File"

  def getCustomMetadata(consignmentId: UUID, clientSecret: String): IO[List[cm.CustomMetadata]] = for {
    token <- keycloak.serviceAccountToken(clientId, clientSecret).toIO
    metadata <- customMetadataClient.getResult(token, cm.document, cm.Variables(consignmentId).some).toIO
    data <- IO.fromOption(metadata.data)(
      new RuntimeException(metadata.errors.map(_.message).headOption.getOrElse("No custom metadata definitions found"))
    )
  } yield data.customMetadata

  def updateConsignmentStatus(consignmentId: UUID, clientSecret: String, statusType: String, statusValue: String): IO[Option[Int]] =
    for {
      token <- keycloak.serviceAccountToken(clientId, clientSecret).toIO
      metadata <- updateConsignmentStatus.getResult(token, ucs.document, ucs.Variables(ConsignmentStatusInput(consignmentId, statusType, statusValue.some)).some).toIO
      data <- IO.fromOption(metadata.data)(
        new RuntimeException(metadata.errors.map(_.message).headOption.getOrElse("Unable to update consignment status"))
      )
    } yield data.updateConsignmentStatus

  def addOrUpdateBulkFileMetadata(consignmentId: UUID, clientSecret: String, fileMetadata: List[AddOrUpdateFileMetadata]): IO[List[AddOrUpdateBulkFileMetadata]] =
    for {
      token <- keycloak.serviceAccountToken(clientId, clientSecret).toIO
      metadata <- addOrUpdateBulkFileMetadata
        .getResult(token, afm.document, afm.Variables(AddOrUpdateBulkFileMetadataInput(consignmentId, fileMetadata)).some, graphqlApiRequestTimeOut)
        .toIO
      data <- IO.fromOption(metadata.data)(
        new RuntimeException(metadata.errors.map(_.message).headOption.getOrElse("Unable to add or update bulk file metadata"))
      )
    } yield data.addOrUpdateBulkFileMetadata

  def getFilesWithUniqueAssetIdKey(consignmentId: UUID, clientSecret: String): IO[Map[String, FileDetail]] = {
    for {
      token <- keycloak.serviceAccountToken(clientId, clientSecret).toIO
      fileFilters = FileFilters(fileTypeIdentifier.some, None, None, None)
      fileUniqueAssetIdKey <- getFilesWithUniqueAssetIdKey.getResult(token, uaik.document, uaik.Variables(consignmentId, fileFilters).some).toIO
      data <- IO.fromOption(fileUniqueAssetIdKey.data)(
        new RuntimeException(fileUniqueAssetIdKey.errors.map(_.message).headOption.getOrElse("Unable to get file unique asset id key"))
      )
    } yield data.getConsignment
      .map(c => c.files.map(f => f.metadata.clientSideOriginalFilePath.getOrElse("") -> FileDetail(f.fileId, f.fileName, f.metadata.clientSideLastModifiedDate)).toMap)
      .getOrElse(throw new RuntimeException("Unable to get FilesWithUniqueAssetIdKey"))
  }

  def updateConsignmentMetadataSchemaLibraryVersion(consignmentId: UUID, clientSecret: String, schemeVersion: String): IO[Option[Int]] = {
    for {
      token <- keycloak.serviceAccountToken(clientId, clientSecret).toIO
      metadata <- updateConsignmentMetadataSchemaLibraryVersion
        .getResult(token, ucslv.document, ucslv.Variables(UpdateMetadataSchemaLibraryVersionInput(consignmentId, schemeVersion)).some)
        .toIO
      data <- IO.fromOption(metadata.data)(new RuntimeException("Unable to update consignment schema library version"))
    } yield data.updateConsignmentMetadataSchemaLibraryVersion
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
      getFilesWithUniqueAssetIdKey: GraphQLClient[uaik.Data, uaik.Variables],
      updateConsignmentMetadataSchemaLibraryVersion: GraphQLClient[ucslv.Data, ucslv.Variables]
  )(implicit
      backend: SttpBackend[Identity, Any],
      keycloakDeployment: TdrKeycloakDeployment
  ): GraphQlApi = {
    val logger: Logger = Logger[GraphQlApi]
    new GraphQlApi(
      keycloak,
      customMetadataClient,
      updateConsignmentStatus,
      addOrUpdateBulkFileMetadata,
      getFilesWithUniqueAssetIdKey,
      updateConsignmentMetadataSchemaLibraryVersion
    )(
      logger,
      keycloakDeployment,
      backend
    )
  }
}

case class FileDetail(fileId: UUID, fileName: Option[String], lastModifiedDate: Option[LocalDateTime]) {

  def getValue(metadataField: String): String = {
    metadataField match {
      case "file_name"          => fileName.getOrElse("")
      case "date_last_modified" => lastModifiedDate.map(_.format(dateTimeFormatter)).getOrElse("")
    }
  }
}
