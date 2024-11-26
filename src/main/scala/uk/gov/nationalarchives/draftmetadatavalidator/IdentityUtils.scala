package uk.gov.nationalarchives.draftmetadatavalidator

import graphql.codegen.GetConsignmentFilesMetadata.getConsignmentFilesMetadata
import graphql.codegen.GetConsignmentFilesMetadata.getConsignmentFilesMetadata.GetConsignment.Files
import uk.gov.nationalarchives.draftmetadatavalidator.Lambda.ValidationParameters
import uk.gov.nationalarchives.tdr.schemautils.SchemaUtils

import java.util.UUID

object IdentityUtils {
  def buildClientToPersistenceIdMap(
      fileIdDataResponse: Option[getConsignmentFilesMetadata.Data],
      validationParameters: ValidationParameters
  ): Map[String, UUID] = {
    val clientId: Files => Option[String] = file => {
      file.fileMetadata
        .find(_.name == SchemaUtils.convertToAlternateKey(validationParameters.persistenceAlternateKey, validationParameters.uniqueAssetIDKey))
        .map(_.value)
    }

    fileIdDataResponse
      .flatMap { fileIdData =>
        fileIdData.getConsignment
          .map(_.files)
          .map { files =>
            files
              .filter { file => file.fileMetadata.exists(fm => fm.name == "FileType" && fm.value == "File") }
              .collect { file => clientId(file) match { case Some(clientId) => (clientId, file.fileId) } }
          }
      }
      .map(_.toMap)
      .getOrElse(Map.empty)
  }
}
