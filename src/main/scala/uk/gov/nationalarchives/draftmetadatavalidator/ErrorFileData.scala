package uk.gov.nationalarchives.draftmetadatavalidator

import uk.gov.nationalarchives.draftmetadatavalidator.FileError.FileError
import uk.gov.nationalarchives.draftmetadatavalidator.Lambda.DraftMetadata
import uk.gov.nationalarchives.tdr.validation.Metadata

import java.text.SimpleDateFormat
import java.util.{Date, UUID}

object FileError extends Enumeration {
  type FileError = Value
  val UTF_8, INVALID_CSV, SCHEMA_REQUIRED, SCHEMA_VALIDATION, None  = Value
}

case class Error(validationProcess: String, property: String, errorKey: String,  message: String)
case class ValidationErrors(assetId: String, errors: Set[Error], data: List[Metadata] = List.empty[Metadata])
case class ErrorFileData(consignmentId: UUID, date: String, fileError: FileError, validationErrors: List[ValidationErrors])


object ErrorFileData {

  def apply(draftMetadata: DraftMetadata, fileError: FileError = FileError.None, validationErrors: List[ValidationErrors] = Nil): ErrorFileData = {

    val pattern = "yyyy-MM-dd"
    val dateFormat = new SimpleDateFormat(pattern)
    ErrorFileData(draftMetadata.consignmentId, dateFormat.format(new Date), fileError, validationErrors)
  }
}
