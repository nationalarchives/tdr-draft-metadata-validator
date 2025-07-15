package uk.gov.nationalarchives.tdr.draftmetadatachecks.validations

import uk.gov.nationalarchives.tdr.draftmetadatachecks.Lambda.ValidationParameters
import uk.gov.nationalarchives.tdr.draftmetadatachecks.{Error, ValidationErrors}
import uk.gov.nationalarchives.tdr.schemautils.ConfigUtils.MetadataConfiguration
import uk.gov.nationalarchives.tdr.validation.schema.ValidationProcess
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Properties
import scala.util.Try

object FOIExemptionDate {

  val FOI_EXEMPTION_DATE: String = "foi_exemption_asserted"
  val CLOSURE_START_DATE: String = "closure_start_date"

  def validateFOIExemptionDate(
      csvData: List[FileRow],
      messageProperties: Properties,
      validationParameters: ValidationParameters,
      metadataConfiguration: MetadataConfiguration
  ): List[ValidationErrors] = {

    val tdrClientHeaderMapper = metadataConfiguration.propertyToOutputMapper(validationParameters.clientAlternateKey)

    val assetIdColumn = tdrClientHeaderMapper(validationParameters.uniqueAssetIdKey)
    val foiExemptionDateColumn = tdrClientHeaderMapper(FOI_EXEMPTION_DATE)
    val closureStartDateColumn = tdrClientHeaderMapper(CLOSURE_START_DATE)

    val foiExemptionDateChecksData: List[FOIExemptionDateCheckData] = csvData.map { fileRow =>
      val foiExemptionAssertedDateValue = fileRow.metadata.find(_.name == foiExemptionDateColumn).map(_.value).getOrElse("")
      val closureStartDateValue = fileRow.metadata.find(_.name == closureStartDateColumn).map(_.value).getOrElse("")
      val assetId = fileRow.metadata.find(_.name == assetIdColumn).map(_.value).getOrElse("")
      FOIExemptionDateCheckData(assetId, foiExemptionAssertedDateValue, closureStartDateValue)
    }

    foiExemptionDateChecksData.map(foiChecksData => {

      val datesSameError =
        if (foiChecksData.foiExemptionDate.nonEmpty && foiChecksData.foiExemptionDate == foiChecksData.closureStartDate)
          generateValidationDatesSameErrors(
            List(foiChecksData),
            messageProperties,
            tdrClientHeaderMapper
          )
        else
          List.empty[ValidationErrors]

      val invalidExemptionDateError =
        if (foiChecksData.foiExemptionDate.nonEmpty) {
          val formatter = DateTimeFormatter.ISO_LOCAL_DATE
          val janFirst2000 = LocalDate.of(2000, 1, 1)

          Try(LocalDate.parse(foiChecksData.foiExemptionDate, formatter))
            .filter(exemptionDate => exemptionDate.isBefore(janFirst2000))
            .map(_ =>
              List(
                ValidationErrors(
                  assetId = foiChecksData.assetId,
                  errors = Set(
                    exemptionDateBefore2000(messageProperties, tdrClientHeaderMapper)
                  ),
                  data = List(
                    Metadata(
                      name = tdrClientHeaderMapper(FOI_EXEMPTION_DATE),
                      value = foiChecksData.foiExemptionDate
                    )
                  )
                )
              )
            )
            .getOrElse(List.empty[ValidationErrors])
        } else {
          List.empty[ValidationErrors]
        }

      datesSameError ++ invalidExemptionDateError
    })
  }.flatten

  private def generateValidationDatesSameErrors(
      foiExemptionDateErrorData: List[FOIExemptionDateCheckData],
      messageProperties: Properties,
      tdrClientHeaderMapper: String => String
  ): List[ValidationErrors] = {
    foiExemptionDateErrorData.map { error =>
      ValidationErrors(
        assetId = error.assetId,
        errors = Set(
          exemptionDateAndClosureStartDateSameError(messageProperties, tdrClientHeaderMapper)
        ),
        data = List(
          Metadata(
            name = tdrClientHeaderMapper(FOI_EXEMPTION_DATE),
            value = error.foiExemptionDate
          )
        )
      )
    }
  }

  private def exemptionDateAndClosureStartDateSameError(messageProperties: Properties, tdrClientHeaderMapper: String => String) = {
    Error(
      validationProcess = ValidationProcess.SCHEMA_CLOSURE_CLOSED.toString,
      property = tdrClientHeaderMapper(FOI_EXEMPTION_DATE),
      errorKey = "exemptionDateAndClosureStartDateSame",
      message = messageProperties.getProperty(
        s"${ValidationProcess.SCHEMA_CLOSURE_CLOSED}.exemptionDateAndClosureStartDateSame",
        "SCHEMA_CLOSURE_CLOSED.exemptionDateAndClosureStartDateSame"
      )
    )
  }

  private def exemptionDateBefore2000(messageProperties: Properties, tdrClientHeaderMapper: String => String) = {
    Error(
      validationProcess = ValidationProcess.SCHEMA_CLOSURE_CLOSED.toString,
      property = tdrClientHeaderMapper(FOI_EXEMPTION_DATE),
      errorKey = "exemptionDateBefore2000",
      message = messageProperties.getProperty(
        s"${ValidationProcess.SCHEMA_CLOSURE_CLOSED}.exemptionDateBefore2000",
        "SCHEMA_CLOSURE_CLOSED.exemptionDateBefore2000"
      )
    )
  }

  private case class FOIExemptionDateCheckData(assetId: String, foiExemptionDate: String, closureStartDate: String)
}
