package uk.gov.nationalarchives.tdr.draftmetadatachecks.validations

import uk.gov.nationalarchives.tdr.draftmetadatachecks.Lambda.ValidationParameters
import uk.gov.nationalarchives.tdr.draftmetadatachecks.{Error, ValidationErrors}
import uk.gov.nationalarchives.tdr.schema.generated.BaseSchema
import uk.gov.nationalarchives.tdr.schemautils.ConfigUtils.MetadataConfiguration
import uk.gov.nationalarchives.tdr.validation.schema.{FileRow, Metadata, ValidationProcess}

import java.util.Properties

object OriginalIdentifier {

  def validateOriginalIdentifier(
      csvData: List[FileRow],
      messageProperties: Properties,
      validationParameters: ValidationParameters,
      metadataConfiguration: MetadataConfiguration
  ): List[ValidationErrors] = {

    val tdrClientHeaderMapper = metadataConfiguration.propertyToOutputMapper(validationParameters.clientAlternateKey)

    val assetIdColumn = tdrClientHeaderMapper(validationParameters.uniqueAssetIdKey)
    val originalIdentifierColumn = tdrClientHeaderMapper(BaseSchema.original_identifier)
    val filePathColumn = tdrClientHeaderMapper(BaseSchema.file_path)
    val allFilePaths = csvData.flatMap(_.metadata.find(_.name == filePathColumn).map(_.value)).toSet

    csvData.flatMap { fileRow =>
      val originalIdentifier = fileRow.metadata.find(_.name == originalIdentifierColumn).map(_.value).getOrElse("")
      val filePath = fileRow.metadata.find(_.name == filePathColumn).map(_.value).getOrElse("")
      val assetId = fileRow.metadata.find(_.name == assetIdColumn).map(_.value).getOrElse("")

      val errorKey = originalIdentifier match {
        case ""                                              => None
        case "No Original Found"                             => None
        case `filePath`                                      => Some("original_identifier.matchFilePath")
        case _ if !allFilePaths.contains(originalIdentifier) => Some("original_identifier.matchFilePath")
        case _                                               => None
      }

      errorKey.map(originalIdentifierError(assetId, originalIdentifier, _, messageProperties, originalIdentifierColumn))
    }
  }

  private def originalIdentifierError(
      assetId: String,
      originalIdentifier: String,
      errorKey: String,
      messageProperties: Properties,
      originalIdentifierColumn: String
  ): ValidationErrors = {
    ValidationErrors(
      assetId = assetId,
      errors = Set(
        Error(
          validationProcess = ValidationProcess.SCHEMA_BASE.toString,
          property = originalIdentifierColumn,
          errorKey = errorKey,
          message = messageProperties.getProperty(
            s"${ValidationProcess.SCHEMA_BASE}.$errorKey",
            s"${ValidationProcess.SCHEMA_BASE}.$errorKey"
          )
        )
      ),
      data = List(
        Metadata(
          name = originalIdentifierColumn,
          value = originalIdentifier
        )
      )
    )
  }
}
