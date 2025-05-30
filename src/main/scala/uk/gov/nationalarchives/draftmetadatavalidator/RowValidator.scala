package uk.gov.nationalarchives.draftmetadatavalidator

import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.util.Properties

object RowValidator {
  def validateMissingRows(
      uniqueAssetIdKeys: Set[String],
      csvData: List[FileRow],
      messageProperties: Properties,
      clientAssetIdKey: String
  ): List[ValidationErrors] =
    (uniqueAssetIdKeys -- csvData.map(_.matchIdentifier).toSet)
      .map(matchIdentifier => toRowValidationErrors(clientAssetIdKey, matchIdentifier, Missing, messageProperties))
      .toList

  def validateUnknownRows(
      uniqueAssetIdKeys: Set[String],
      csvData: List[FileRow],
      messageProperties: Properties,
      clientAssetIdKey: String
  ): List[ValidationErrors] =
    (csvData.map(_.matchIdentifier).toSet -- uniqueAssetIdKeys)
      .map(matchIdentifier => toRowValidationErrors(clientAssetIdKey, matchIdentifier, Unknown, messageProperties))
      .toList

  def validateDuplicateRows(
      csvData: List[FileRow],
      messageProperties: Properties,
      clientAssetIdKey: String
  ): List[ValidationErrors] =
    csvData
      .map(_.matchIdentifier)
      .diff(csvData.map(_.matchIdentifier).distinct)
      .map(matchIdentifier => toRowValidationErrors(clientAssetIdKey, matchIdentifier, Duplicate, messageProperties))

  private def toRowValidationErrors(
      clientAssetIdKey: String,
      fileMatchIdentifier: String,
      errorType: RowErrorType,
      messageProperties: Properties
  ): ValidationErrors = {
    ValidationErrors(
      assetId = fileMatchIdentifier,
      errors = Set(
        Error(
          validationProcess = s"${FileError.ROW_VALIDATION}",
          property = "",
          errorKey = errorType.name,
          message = messageProperties.getProperty(s"${FileError.ROW_VALIDATION}.${errorType.name}", s"${FileError.ROW_VALIDATION}.${errorType.name}")
        )
      ),
      data = List(Metadata(clientAssetIdKey, fileMatchIdentifier))
    )
  }

  private sealed trait RowErrorType { val name: String }
  private case object Missing extends RowErrorType { val name = "missing" }
  private case object Duplicate extends RowErrorType { val name = "duplicate" }
  private case object Unknown extends RowErrorType { val name = "unknown" }
}
