package uk.gov.nationalarchives.tdr.draftmetadatachecks.validations

import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.nationalarchives.tdr.draftmetadatachecks.Lambda.ValidationParameters
import uk.gov.nationalarchives.tdr.draftmetadatachecks.validations.FOIExemptionDate.{CLOSURE_START_DATE, FOI_EXEMPTION_DATE}
import uk.gov.nationalarchives.tdr.schemautils.ConfigUtils
import uk.gov.nationalarchives.tdr.schemautils.ConfigUtils.ARRAY_SPLIT_CHAR
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.util.{Properties, UUID}
import scala.util.Random

class FOIExemptionDateSpec extends AnyWordSpec {

  implicit val metadataConfiguration: ConfigUtils.MetadataConfiguration = ConfigUtils.loadConfiguration

  private val foiExemptionCodeCol = metadataConfiguration.propertyToOutputMapper("tdrFileHeader")(FOI_EXEMPTION_DATE)
  private val closureStartDateCol = metadataConfiguration.propertyToOutputMapper("tdrFileHeader")(CLOSURE_START_DATE)

  "FOIExemptionDate.validateFOIExemptionDate" should {
    "validate foi exemption date and start date are not the same when foi exemption date not empty" in {
      val csvRowData = Map(foiExemptionCodeCol -> "2023-12-13", closureStartDateCol -> "2023-12-13")

      val testData = createInputForFOIExemptionDateValidation(List(csvRowData))
      val result = FOIExemptionDate.validateFOIExemptionDate(testData._1, testData._2, testData._3, metadataConfiguration)
      result.length shouldBe 1
      result.head.errors.toList.length shouldBe 1
      result.head.errors.toList.count(error => error.property == foiExemptionCodeCol) shouldBe 1
      result.head.errors.toList.count(error =>
        error.message == "Must be the date of the advisory schedule confirming closure, should not be the same as closure start date"
      ) shouldBe 1
      result.head.data.filter(data => data.name == foiExemptionCodeCol).map(_.value).toList shouldBe List("2023-12-13")
    }

    "validate with no errors if exemption date is empty" in {
      val row = Map(foiExemptionCodeCol -> "")

      val testData = createInputForFOIExemptionDateValidation(List(row))
      val result = FOIClosureCodesAndPeriods.foiCodesPeriodsConsistent(testData._1, testData._2, testData._3, metadataConfiguration)

      result.length shouldBe 0
    }

    "validate exemption date 1999-12-31 is invalid" in {
      val csvRowData = Map(foiExemptionCodeCol -> "1999-12-31")

      val testData = createInputForFOIExemptionDateValidation(List(csvRowData))
      val result = FOIExemptionDate.validateFOIExemptionDate(testData._1, testData._2, testData._3, metadataConfiguration)
      result.length shouldBe 1
      result.head.errors.toList.length shouldBe 1
      result.head.errors.toList.count(error => error.property == foiExemptionCodeCol) shouldBe 1
      result.head.errors.toList.count(error => error.message == "Invalid FOI exemption date") shouldBe 1
      result.head.data.filter(data => data.name == foiExemptionCodeCol).map(_.value) shouldBe List("1999-12-31")
    }

    "validate exemption date 2000-01-01 is valid" in {
      val csvRowData = Map(foiExemptionCodeCol -> "2000-01-01")

      val testData = createInputForFOIExemptionDateValidation(List(csvRowData))
      val result = FOIExemptionDate.validateFOIExemptionDate(testData._1, testData._2, testData._3, metadataConfiguration)
      result.length shouldBe 0
    }

    "validate exemption date after 2000-01-01 is valid" in {
      val csvRowData = Map(foiExemptionCodeCol -> "2023-01-01")

      val testData = createInputForFOIExemptionDateValidation(List(csvRowData))
      val result = FOIExemptionDate.validateFOIExemptionDate(testData._1, testData._2, testData._3, metadataConfiguration)
      result.length shouldBe 0
    }
  }

  private def createInputForFOIExemptionDateValidation(
      csvData: List[Map[String, String]]
  ): (List[FileRow], Properties, ValidationParameters) = {

    val messageProperties = new Properties()
    messageProperties.setProperty(
      "SCHEMA_CLOSURE_CLOSED.exemptionDateAndClosureStartDateSame",
      "Must be the date of the advisory schedule confirming closure, should not be the same as closure start date"
    )
    messageProperties.setProperty("SCHEMA_CLOSURE_CLOSED.exemptionDateBefore2000", "Invalid FOI exemption date")

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
