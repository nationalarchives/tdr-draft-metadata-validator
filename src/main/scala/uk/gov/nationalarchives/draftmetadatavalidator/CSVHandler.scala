package uk.gov.nationalarchives.draftmetadatavalidator

import com.github.tototoshi.csv.{CSVReader, CSVWriter}
import uk.gov.nationalarchives.tdr.schemautils.SchemaUtils
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Paths}

object CSVHandler {

  /** Reads a CSV file into a list of FileRows The FileRow.fileName
    * @param filePath
    *   path to the csv data
    * @param inputHeaderKey
    *   the alternateKey in the metadata schema to the header in the source data
    * @param outputHeaderKey
    *   the alternateKey in the metadata schema to the value to be used for Metadata.name in the output FileRows
    * @param uniqueAssetIdKey
    *   the name of the metadata schema property to be used to uniquely identify metadata entries
    * @return
    *   List of FileRows
    */
  def loadCSV(filePath: String, inputHeaderKey: String, outputHeaderKey: String, uniqueAssetIdKey: String): List[FileRow] = {
    val convertHeaders: (String, String) => (String, String) = { case (originalHeader, value) =>
      (SchemaUtils.convertToAlternateKey(outputHeaderKey, SchemaUtils.convertToValidationKey(inputHeaderKey, originalHeader)), value)
    }
    val reader = CSVReader.open(filePath)
    val all: Seq[Map[String, String]] = reader.allWithHeaders().map(_.map({ case (k, v) => convertHeaders(k, v) }))
    all.map { row =>
      val keyValue = SchemaUtils.convertToAlternateKey(outputHeaderKey, uniqueAssetIdKey)
      FileRow(
        matchIdentifier = row.getOrElse(keyValue, keyValue),
        metadata = row.collect {
          case (columnHeader, value) if columnHeader.nonEmpty => Metadata(columnHeader, value)
        }.toList
      )
    }.toList
  }

  def loadHeaders(filePath: String): Option[List[String]] = {
    CSVReader.open(filePath).readNext()
  }

  def writeCsv(rows: List[List[String]], filePath: String): Unit = {
    val bas = new ByteArrayOutputStream()
    val writer = CSVWriter.open(bas)
    writer.writeAll(rows)
    Files.writeString(Paths.get(filePath), bas.toString("UTF-8"))
  }
}
