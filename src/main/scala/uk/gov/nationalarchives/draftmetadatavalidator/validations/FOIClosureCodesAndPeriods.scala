package uk.gov.nationalarchives.draftmetadatavalidator.validations

import uk.gov.nationalarchives.draftmetadatavalidator.Lambda.ValidationParameters
import uk.gov.nationalarchives.draftmetadatavalidator.{Error, FileError, ValidationErrors}
import uk.gov.nationalarchives.tdr.schemautils.SchemaUtils
import uk.gov.nationalarchives.tdr.validation.utils.ConfigUtils.ARRAY_SPLIT_CHAR
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.util.Properties

object FOIClosureCodesAndPeriods {

  def foiCodesPeriodsConsistent(csvData: List[FileRow], messageProperties: Properties, validationParameters: ValidationParameters): List[ValidationErrors] = {

    val assetIdColumn = SchemaUtils.convertToAlternateKey(validationParameters.clientAlternateKey, validationParameters.uniqueAssetIdKey)
    val closureCodeColumn = SchemaUtils.convertToAlternateKey(validationParameters.clientAlternateKey, "foi_exemption_code")
    val closurePeriodColumn = SchemaUtils.convertToAlternateKey(validationParameters.clientAlternateKey, "closure_period")

    val closureCodeAndPeriods: List[ClosureCheckData] = csvData.map { fileRow =>
      val closureCode = fileRow.metadata.find(_.name == closureCodeColumn).map(_.value).getOrElse("")
      val closurePeriod = fileRow.metadata.find(_.name == closurePeriodColumn).map(_.value).getOrElse("")
      val assetId = fileRow.metadata.find(_.name == assetIdColumn).map(_.value).getOrElse("")
      ClosureCheckData(assetId, closureCode, closurePeriod)
    }
    val closureCodeAndPeriodsWithErrors = closureCodeAndPeriods.filter { data =>
      data.closureCode.split(ARRAY_SPLIT_CHAR).length != data.closurePeriod.split(ARRAY_SPLIT_CHAR).length
    }

    if (closureCodeAndPeriodsWithErrors.isEmpty) {
      List.empty[ValidationErrors]
    } else {
      generateValidationErrors(closureCodeAndPeriodsWithErrors, validationParameters, messageProperties)
    }
  }

  private def generateValidationErrors(closuresWithCodeAndPeriodMismatch: List[ClosureCheckData], validationParameters: ValidationParameters, messageProperties: Properties) = {
    closuresWithCodeAndPeriodMismatch.map { error =>
      ValidationErrors(
        assetId = error.assetId,
        errors = Set(
          misMatchError("foi_exemption_code", messageProperties),
          misMatchError("closure_period", messageProperties)
        ),
        data = List(
          Metadata(
            name = SchemaUtils.convertToAlternateKey(
              alternateKeyName = validationParameters.clientAlternateKey,
              propertyKey = "foi_exemption_code"
            ),
            value = error.closureCode
          ),
          Metadata(
            name = SchemaUtils.convertToAlternateKey(
              alternateKeyName = validationParameters.clientAlternateKey,
              propertyKey = "closure_period"
            ),
            value = error.closurePeriod
          )
        )
      )
    }
  }

  private def misMatchError(propertyName: String, messageProperties: Properties) = {
    Error(
      validationProcess = s"${FileError.ROW_VALIDATION}",
      property = propertyName,
      errorKey = "closureCodeAndPeriodMismatch",
      message = messageProperties.getProperty(
        s"${FileError.ROW_VALIDATION}.closureCodeAndPeriodMismatch",
        s"${FileError.ROW_VALIDATION}.closureCodeAndPeriodMismatch"
      )
    )
  }

  private case class ClosureCheckData(assetId: String, closureCode: String, closurePeriod: String)
}
