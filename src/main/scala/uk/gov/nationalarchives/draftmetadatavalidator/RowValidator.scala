package uk.gov.nationalarchives.draftmetadatavalidator

import uk.gov.nationalarchives.draftmetadatavalidator.Lambda.ValidationParameters
import uk.gov.nationalarchives.tdr.schemautils.SchemaUtils
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.util.Properties

object RowValidator {
  def validateMissingRows(
      uniqueAssetIdKeys: Set[String],
      csvData: List[FileRow],
      messageProperties: Properties,
      validationParameters: ValidationParameters
  ): List[ValidationErrors] =
    (uniqueAssetIdKeys -- csvData.map(_.matchIdentifier).toSet)
      .map(id => toRowValidationErrors(id, Missing, messageProperties, validationParameters))
      .toList

  def validateUnknownRows(
      uniqueAssetIdKeys: Set[String],
      csvData: List[FileRow],
      messageProperties: Properties,
      validationParameters: ValidationParameters
  ): List[ValidationErrors] =
    (csvData.map(_.matchIdentifier).toSet -- uniqueAssetIdKeys).map(id => toRowValidationErrors(id, Unknown, messageProperties, validationParameters)).toList

  def validateDuplicateRows(
      csvData: List[FileRow],
      messageProperties: Properties,
      validationParameters: ValidationParameters
  ): List[ValidationErrors] =
    csvData
      .map(_.matchIdentifier)
      .diff(csvData.map(_.matchIdentifier).distinct)
      .map(id => toRowValidationErrors(id, Duplicate, messageProperties, validationParameters))

  def toRowValidationErrors(
      clientIdentifier: String,
      errorType: RowErrorType,
      messageProperties: Properties,
      validationParameters: ValidationParameters
  ): ValidationErrors = {
    ValidationErrors(
      assetId = clientIdentifier,
      errors = Set(
        Error(
          validationProcess = s"${FileError.ROW_VALIDATION}",
          property = "",
          errorKey = errorType.name,
          message = messageProperties.getProperty(
            s"${FileError.ROW_VALIDATION}.${errorType.name}",
            s"${FileError.ROW_VALIDATION}.${errorType.name}"
          )
        )
      ),
      data = List(
        Metadata(
          name = SchemaUtils.convertToAlternateKey(
            alternateKeyName = validationParameters.clientAlternateKey,
            propertyKey = validationParameters.uniqueAssetIdKey
          ),
          value = clientIdentifier
        )
      )
    )
  }

  sealed trait RowErrorType { val name: String }
  case object Missing extends RowErrorType { val name = "missing" }
  case object Duplicate extends RowErrorType { val name = "duplicate" }
  case object Unknown extends RowErrorType { val name = "unknown" }
}
