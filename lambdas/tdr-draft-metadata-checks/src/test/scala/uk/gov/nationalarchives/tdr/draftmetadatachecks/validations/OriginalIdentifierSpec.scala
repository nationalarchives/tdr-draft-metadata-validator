package uk.gov.nationalarchives.tdr.draftmetadatachecks.validations

import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.nationalarchives.tdr.draftmetadatachecks.Lambda.ValidationParameters
import uk.gov.nationalarchives.tdr.schema.generated.BaseSchema
import uk.gov.nationalarchives.tdr.schemautils.ConfigUtils
import uk.gov.nationalarchives.tdr.validation.schema.{FileRow, Metadata, ValidationProcess}

import java.util.{Properties, UUID}

class OriginalIdentifierSpec extends AnyWordSpec {

  implicit val metadataConfiguration: ConfigUtils.MetadataConfiguration = ConfigUtils.loadConfiguration

  private val clientHeaderMapper = metadataConfiguration.propertyToOutputMapper("tdrFileHeader")
  private val originalIdentifierCol = clientHeaderMapper(BaseSchema.original_identifier)
  private val filePathCol = clientHeaderMapper(BaseSchema.file_path)

  private val matchFilePathMessage = "Original identifier must match the file path of another record"

  private case class CsvRow(filePath: String, originalIdentifier: String)

  "OriginalIdentifier.validateOriginalIdentifier" should {
    "validate with no errors when the original identifier matches the file path of another record" in {
      val csvData = List(
        CsvRow(filePath = "data/original.docx", originalIdentifier = ""),
        CsvRow(filePath = "data/redacted.pdf", originalIdentifier = "data/original.docx")
      )

      val (fileRows, messageProperties, validationParameters) = createInputForOriginalIdentifierValidation(csvData)
      val result = OriginalIdentifier.validateOriginalIdentifier(fileRows, messageProperties, validationParameters, metadataConfiguration)

      result shouldBe empty
    }

    "validate with no errors when the original identifier is empty" in {
      val csvData = List(CsvRow(filePath = "data/original.docx", originalIdentifier = ""))

      val (fileRows, messageProperties, validationParameters) = createInputForOriginalIdentifierValidation(csvData)
      val result = OriginalIdentifier.validateOriginalIdentifier(fileRows, messageProperties, validationParameters, metadataConfiguration)

      result shouldBe empty
    }

    "return an error when the original identifier is the record's own file path" in {
      val csvData = List(CsvRow(filePath = "data/original.docx", originalIdentifier = "data/original.docx"))

      val (fileRows, messageProperties, validationParameters) = createInputForOriginalIdentifierValidation(csvData)
      val result = OriginalIdentifier.validateOriginalIdentifier(fileRows, messageProperties, validationParameters, metadataConfiguration)

      result.length shouldBe 1
      result.head.assetId shouldBe "data/original.docx"
      val errors = result.head.errors.toList
      errors.length shouldBe 1
      errors.head.validationProcess shouldBe ValidationProcess.SCHEMA_BASE.toString
      errors.head.property shouldBe originalIdentifierCol
      errors.head.errorKey shouldBe "original_identifier.matchFilePath"
      errors.head.message shouldBe matchFilePathMessage
      result.head.data shouldBe List(Metadata(name = originalIdentifierCol, value = "data/original.docx"))
    }

    "return an error when the original identifier is not one of the file paths" in {
      val csvData = List(
        CsvRow(filePath = "data/original.docx", originalIdentifier = ""),
        CsvRow(filePath = "data/redacted.pdf", originalIdentifier = "data/missing.docx")
      )

      val (fileRows, messageProperties, validationParameters) = createInputForOriginalIdentifierValidation(csvData)
      val result = OriginalIdentifier.validateOriginalIdentifier(fileRows, messageProperties, validationParameters, metadataConfiguration)

      result.length shouldBe 1
      result.head.assetId shouldBe "data/redacted.pdf"
      val errors = result.head.errors.toList
      errors.length shouldBe 1
      errors.head.property shouldBe originalIdentifierCol
      errors.head.errorKey shouldBe "original_identifier.matchFilePath"
      errors.head.message shouldBe matchFilePathMessage
      result.head.data shouldBe List(Metadata(name = originalIdentifierCol, value = "data/missing.docx"))
    }

    "return an error for each invalid record" in {
      val csvData = List(
        CsvRow(filePath = "data/original.docx", originalIdentifier = "data/original.docx"),
        CsvRow(filePath = "data/redacted.pdf", originalIdentifier = "data/missing.docx"),
        CsvRow(filePath = "data/valid.pdf", originalIdentifier = "data/original.docx")
      )

      val (fileRows, messageProperties, validationParameters) = createInputForOriginalIdentifierValidation(csvData)
      val result = OriginalIdentifier.validateOriginalIdentifier(fileRows, messageProperties, validationParameters, metadataConfiguration)

      result.length shouldBe 2
      result.map(_.assetId) shouldBe List("data/original.docx", "data/redacted.pdf")
      result.flatMap(_.errors.map(_.errorKey)) shouldBe List("original_identifier.matchFilePath", "original_identifier.matchFilePath")
    }
  }

  private def createInputForOriginalIdentifierValidation(
      csvData: List[CsvRow]
  ): (List[FileRow], Properties, ValidationParameters) = {

    val messageProperties = new Properties()
    messageProperties.setProperty(s"${ValidationProcess.SCHEMA_BASE}.original_identifier.matchFilePath", matchFilePathMessage)

    val validationParameters = ValidationParameters(
      consignmentId = UUID.randomUUID(),
      schemaToValidate = Set.empty,
      uniqueAssetIdKey = "file_path",
      clientAlternateKey = "tdrFileHeader",
      persistenceAlternateKey = "persistenceKey",
      expectedPropertyField = "expectedField"
    )

    val fileRows = csvData.map { row =>
      FileRow(
        matchIdentifier = row.filePath,
        metadata = List(
          Metadata(name = filePathCol, value = row.filePath),
          Metadata(name = originalIdentifierCol, value = row.originalIdentifier)
        )
      )
    }
    (fileRows, messageProperties, validationParameters)
  }
}
