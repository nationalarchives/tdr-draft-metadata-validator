package uk.gov.nationalarchives.draftmetadatavalidator.validations

import uk.gov.nationalarchives.draftmetadatavalidator.Lambda.ValidationParameters
import uk.gov.nationalarchives.draftmetadatavalidator.{Error, FileError, ValidationErrors}
import uk.gov.nationalarchives.tdr.schemautils.ConfigUtils.{ARRAY_SPLIT_CHAR, MetadataConfiguration}
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.util.Properties

object FOIClosureCodesAndPeriods {

  def foiCodesPeriodsConsistent(csvData: List[FileRow], messageProperties: Properties, validationParameters: ValidationParameters)(implicit
      metadataConfiguration: MetadataConfiguration
  ): List[ValidationErrors] = {

    val tdrClientHeaderMapper = metadataConfiguration.propertyToOutputMapper(validationParameters.clientAlternateKey)

    val assetIdColumn = tdrClientHeaderMapper(validationParameters.uniqueAssetIdKey)
    val closureCodeColumn = tdrClientHeaderMapper("foi_exemption_code")
    val closurePeriodColumn = tdrClientHeaderMapper("closure_period")

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
      generateValidationErrors(closureCodeAndPeriodsWithErrors, messageProperties, tdrClientHeaderMapper)
    }
  }

  private def generateValidationErrors(
      closuresWithCodeAndPeriodMismatch: List[ClosureCheckData],
      messageProperties: Properties,
      tdrClientHeaderMapper: String => String
  ): List[ValidationErrors] = {
    closuresWithCodeAndPeriodMismatch.map { error =>
      ValidationErrors(
        assetId = error.assetId,
        errors = Set(
          misMatchError("foi_exemption_code", messageProperties),
          misMatchError("closure_period", messageProperties)
        ),
        data = List(
          Metadata(
            name = tdrClientHeaderMapper("foi_exemption_code"),
            value = error.closureCode
          ),
          Metadata(
            name = tdrClientHeaderMapper("closure_period"),
            value = error.closurePeriod
          )
        )
      )
    }
  }

  private def misMatchError(propertyName: String, messageProperties: Properties) = {
    Error(
      validationProcess = "SCHEMA_CLOSURE_CLOSED",
      property = propertyName,
      errorKey = "closureCodeAndPeriodMismatch",
      message = messageProperties.getProperty(
        "SCHEMA_CLOSURE_CLOSED.closureCodeAndPeriodMismatch"
      )
    )
  }

  private case class ClosureCheckData(assetId: String, closureCode: String, closurePeriod: String)
}
