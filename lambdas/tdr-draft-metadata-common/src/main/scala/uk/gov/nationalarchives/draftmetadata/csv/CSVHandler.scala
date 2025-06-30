package uk.gov.nationalarchives.draftmetadata.csv

import com.github.tototoshi.csv.{CSVReader, CSVWriter}
import uk.gov.nationalarchives.draftmetadata.{FileRow, Metadata}
import uk.gov.nationalarchives.tdr.schemautils.ConfigUtils.MetadataConfiguration

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
  def loadCSV(filePath: String, inputHeaderKey: String, outputHeaderKey: String, uniqueAssetIdKey: String)(implicit metadataConfiguration: MetadataConfiguration): List[FileRow] = {

    val inputToPropertyMapper = metadataConfiguration.inputToPropertyMapper(inputHeaderKey)
    val propertyToOutputMapper = metadataConfiguration.propertyToOutputMapper(outputHeaderKey)

    val convertHeaders: (String, String) => (String, String) = { case (originalHeader, value) =>
      (propertyToOutputMapper(inputToPropertyMapper(originalHeader)), value)
    }
    val reader = CSVReader.open(filePath)
    val all: Seq[Map[String, String]] = reader.allWithHeaders().map(_.map({ case (k, v) => convertHeaders(k, v) }))
    val allWithoutEmptyRows = all.filter(_.values.exists(_.nonEmpty))
    allWithoutEmptyRows.map { row =>
      val keyValue = propertyToOutputMapper(uniqueAssetIdKey)
      FileRow(
        matchIdentifier = row.getOrElse(keyValue, keyValue),
        metadata = row.collect {
          case (columnHeader, value) if columnHeader.nonEmpty => Metadata(columnHeader, value)
        }.toList
      )
    }.toList
  }

  def writeCsv(rows: List[List[String]], filePath: String): Unit = {
    val bas = new ByteArrayOutputStream()
    val writer = CSVWriter.open(bas)
    writer.writeAll(rows)
    Files.writeString(Paths.get(filePath), bas.toString("UTF-8"))
  }
}
