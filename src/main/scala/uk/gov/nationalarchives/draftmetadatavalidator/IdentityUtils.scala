package uk.gov.nationalarchives.draftmetadatavalidator

import graphql.codegen.GetConsignmentFilesMetadata.getConsignmentFilesMetadata.GetConsignment.Files
import graphql.codegen.GetCustomMetadata.customMetadata.CustomMetadata
import graphql.codegen.types.DataType
import uk.gov.nationalarchives.draftmetadatavalidator.Lambda.ValidationParameters
import uk.gov.nationalarchives.tdr.validation
import uk.gov.nationalarchives.tdr.validation.{FileRow, MetadataCriteria, MetadataValidation}

import java.util.UUID

object IdentityUtils {
  def buildLookupMaps(
    clientMetadata: Seq[FileRow], 
    persistedFileMetadata: Seq[Files], 
    validationParameters: ValidationParameters
  ): LookupData = ???
  
  case class LookupData(
    persistenceIdToClientId: Map[UUID, Option[String]],
    clientIdToPersistenceId: Map[String, Option[UUID]]
  )

}
