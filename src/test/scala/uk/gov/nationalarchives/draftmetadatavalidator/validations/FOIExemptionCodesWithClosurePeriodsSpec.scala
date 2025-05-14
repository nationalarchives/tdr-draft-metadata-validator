package uk.gov.nationalarchives.draftmetadatavalidator.validations

import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.nationalarchives.draftmetadatavalidator.Lambda.ValidationParameters
import uk.gov.nationalarchives.tdr.schemautils.SchemaUtils
import uk.gov.nationalarchives.tdr.validation.utils.ConfigUtils.ARRAY_SPLIT_CHAR
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.util.{Properties, UUID}
import scala.util.Random

class FOIExemptionCodesWithClosurePeriodsSpec extends AnyWordSpec {

  private val foiExemptionCodeCol = SchemaUtils.convertToAlternateKey("tdrFileHeader", "foi_exemption_code")
  private val closurePeriodCol = SchemaUtils.convertToAlternateKey("tdrFileHeader", "closure_period")

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
    }
  }

  private def createInputForClosureValidation(
      csvData: List[Map[String, String]]
  ): (List[FileRow], Properties, ValidationParameters) = {

    val messageProperties = new Properties()
    messageProperties.setProperty("ROW_VALIDATION.closureCodeAndPeriodMismatch", "Must have the same number of closure periods as foi exemption codes")

    val validationParameters = ValidationParameters(
      consignmentId = UUID.randomUUID(),
      schemaToValidate = Set.empty,
      uniqueAssetIdKey = "file_path",
      clientAlternateKey = "tdrFileHeader",
      persistenceAlternateKey = "persistenceKey",
      expectedPropertyField = "expectedField"
    )

    val assetIdColumn = SchemaUtils.convertToAlternateKey(validationParameters.clientAlternateKey, validationParameters.uniqueAssetIdKey)
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
