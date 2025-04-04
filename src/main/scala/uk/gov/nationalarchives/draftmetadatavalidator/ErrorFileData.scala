package uk.gov.nationalarchives.draftmetadatavalidator

import cats.Semigroup
import uk.gov.nationalarchives.draftmetadatavalidator.FileError.FileError
import uk.gov.nationalarchives.draftmetadatavalidator.Lambda.ValidationParameters
import uk.gov.nationalarchives.draftmetadatavalidator.utils.MetadataUtils.dateTimeFormatter
import uk.gov.nationalarchives.tdr.validation.Metadata

import java.time.LocalDateTime
import java.util.UUID

object FileError extends Enumeration {
  type FileError = Value
  val UTF_8, INVALID_CSV, ROW_VALIDATION, SCHEMA_REQUIRED, DUPLICATE_HEADER, SCHEMA_VALIDATION, PROTECTED_FIELD, UNKNOWN, None = Value
}

case class Error(validationProcess: String, property: String, errorKey: String, message: String)
case class ValidationErrors(assetId: String, errors: Set[Error], data: List[Metadata] = List.empty[Metadata])

object ValidationErrors {
  implicit val combineValidationErrors: Semigroup[List[ValidationErrors]] =
    Semigroup.instance[List[ValidationErrors]] { (validationErrors, moreValidationErrors) =>
      (validationErrors ++ moreValidationErrors)
        .groupBy(_.assetId)
        .map { case (id, validationErrors) =>
          ValidationErrors(
            assetId = id,
            errors = validationErrors.flatMap(_.errors).toSet,
            data = validationErrors.flatMap(_.data).distinct
          )
        }
        .toList
    }
}
case class ErrorFileData(consignmentId: UUID, date: String, fileError: FileError, validationErrors: List[ValidationErrors])

object ErrorFileData {

  def apply(draftMetadata: ValidationParameters, fileError: FileError = FileError.None, validationErrors: List[ValidationErrors] = Nil): ErrorFileData = {
    ErrorFileData(draftMetadata.consignmentId, LocalDateTime.now().format(dateTimeFormatter), fileError, validationErrors)
  }
}
