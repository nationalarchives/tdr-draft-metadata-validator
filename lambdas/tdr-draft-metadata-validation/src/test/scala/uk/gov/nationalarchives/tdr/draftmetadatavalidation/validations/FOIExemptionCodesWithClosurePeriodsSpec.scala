package uk.gov.nationalarchives.tdr.draftmetadatavalidation.validations

import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.nationalarchives.tdr.draftmetadatavalidation.Lambda.ValidationParameters
import uk.gov.nationalarchives.tdr.schemautils.ConfigUtils
import uk.gov.nationalarchives.tdr.schemautils.ConfigUtils.ARRAY_SPLIT_CHAR
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.util.{Properties, UUID}
import scala.util.Random

class FOIExemptionCodesWithClosurePeriodsSpec extends AnyWordSpec {

  implicit val metadataConfiguration: ConfigUtils.MetadataConfiguration = ConfigUtils.loadConfiguration

  private val foiExemptionCodeCol = metadataConfiguration.propertyToOutputMapper("tdrFileHeader")("foi_exemption_code")
  private val closurePeriodCol = metadataConfiguration.propertyToOutputMapper("tdrFileHeader")("closure_period")

  "FOIClosureCodesAndPeriods.foiCodesPeriodsConsistent" should {
    "validate a closure period and foi exemption code same length" in {
      val row1 = Map(closurePeriodCol -> s"1${ARRAY_SPLIT_CHAR}150", foiExemptionCodeCol -> s"33${ARRAY_SPLIT_CHAR}34")
      val row2 = Map(closurePeriodCol -> s"11${ARRAY_SPLIT_CHAR}150", foiExemptionCodeCol -> s"23${ARRAY_SPLIT_CHAR}56")
      val testData = createInputForClosureValidation(List(row1, row2))
      val result = FOIClosureCodesAndPeriods.foiCodesPeriodsConsistent(testData._1, testData._2, testData._3)
      result.length shouldBe 0
    }

    "validate a closure period and foi exemption code different length" in {
      val row = Map(closurePeriodCol -> s"1${ARRAY_SPLIT_CHAR}150", foiExemptionCodeCol -> s"33${ARRAY_SPLIT_CHAR}34${ARRAY_SPLIT_CHAR}35")

      val testData = createInputForClosureValidation(List(row))
      val result = FOIClosureCodesAndPeriods.foiCodesPeriodsConsistent(testData._1, testData._2, testData._3)

      result.length shouldBe 1
      result.head.errors.head.message shouldBe "Must have the same number of closure periods as foi exemption codes"
    }
  }

  private def createInputForClosureValidation(
      csvData: List[Map[String, String]]
  ): (List[FileRow], Properties, ValidationParameters) = {

    val messageProperties = new Properties()
    messageProperties.setProperty("SCHEMA_CLOSURE_CLOSED.closureCodeAndPeriodMismatch", "Must have the same number of closure periods as foi exemption codes")

    val validationParameters = ValidationParameters(
      consignmentId = UUID.randomUUID(),
      schemaToValidate = Set.empty,
      uniqueAssetIdKey = "file_path",
      clientAlternateKey = "tdrFileHeader",
      persistenceAlternateKey = "persistenceKey",
      expectedPropertyField = "expectedField"
    )

    val assetIdColumn = metadataConfiguration.propertyToOutputMapper(validationParameters.clientAlternateKey)(validationParameters.uniqueAssetIdKey)
    val fileRows = csvData.map { row =>
      val uniqueAssetId = Random.nextInt().toString
      FileRow(
        matchIdentifier = uniqueAssetId,
        metadata = row.map { case (key, value) =>
          Metadata(name = key, value = value)
        }.toList :+ Metadata(name = assetIdColumn, value = uniqueAssetId)
      )
    }
    (fileRows, messageProperties, validationParameters)
  }
}
