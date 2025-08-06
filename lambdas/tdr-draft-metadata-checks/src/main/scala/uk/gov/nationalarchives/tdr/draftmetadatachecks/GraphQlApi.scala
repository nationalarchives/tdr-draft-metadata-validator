package uk.gov.nationalarchives.tdr.draftmetadatachecks

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import com.typesafe.scalalogging.Logger
import graphql.codegen.GetFilesWithUniqueAssetIdKey.{getFilesWithUniqueAssetIdKey => uaik}
import graphql.codegen.types._
import sttp.client3._
import uk.gov.nationalarchives.draftmetadata.config.ApplicationConfig.clientId
import uk.gov.nationalarchives.draftmetadata.utils.MetadataUtils.dateTimeFormatter
import uk.gov.nationalarchives.tdr.GraphQLClient
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.Future

class GraphQlApi(
    keycloak: KeycloakUtils,
    getFilesWithUniqueAssetIdKey: GraphQLClient[uaik.Data, uaik.Variables]
)(implicit
    logger: Logger,
    keycloakDeployment: TdrKeycloakDeployment,
    backend: SttpBackend[Identity, Any]
) {

  private val fileTypeIdentifier: String = "File"

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

  implicit class FutureUtils[T](f: Future[T]) {
    def toIO: IO[T] = IO.fromFuture(IO(f))
  }
}

object GraphQlApi {
  def apply(
      keycloak: KeycloakUtils,
      getFilesWithUniqueAssetIdKey: GraphQLClient[uaik.Data, uaik.Variables]
  )(implicit
      backend: SttpBackend[Identity, Any],
      keycloakDeployment: TdrKeycloakDeployment
  ): GraphQlApi = {
    val logger: Logger = Logger[GraphQlApi]
    new GraphQlApi(
      keycloak,
      getFilesWithUniqueAssetIdKey
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
      case "rights_copyright"   => "Crown copyright" // Should always be this whilst set as a System property
    }
  }
}
