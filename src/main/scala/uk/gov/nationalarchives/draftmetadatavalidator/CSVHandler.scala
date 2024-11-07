package uk.gov.nationalarchives.draftmetadatavalidator

import com.github.tototoshi.csv.{CSVReader, CSVWriter}
import uk.gov.nationalarchives.draftmetadatavalidator.Lambda.ValidationParameters
import uk.gov.nationalarchives.tdr.schemautils.SchemaUtils
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Paths}

class CSVHandler {

  @deprecated
  def loadCSV(filePath: String, metadataNames: List[String]): FileData = {
    val reader = CSVReader.open(filePath)
    val allRowsWithHeader = reader.all()
    val fileRows = allRowsWithHeader match {
      case _ :: rows =>
        rows.map { data =>
          FileRow(
            data.last,
            metadataNames.dropRight(1).zipWithIndex.map { case (name, index) => Metadata(name, data(index)) }
          )
        }
    }
    FileData(allRowsWithHeader, fileRows)
  }

  /** Reads a CSV file into a list of FileRows The FileRow.fileName is the identifier for the row and has been used to store the UUID in above loadCSV def (expecting the UUID to be
    * in the last column). What the identifier to be used is to be decided FileRow metadata key(header) unaltered and the value maintained as a string
    * @param filePath
    *   path to csv uniqueRowKey each row will be keyed to a column value that is unique
    * @return
    *   List of FileRows
    */
  def loadCSV(filePath: String, inputHeaderKey: String, outputHeaderKey: String, uniqueAssetIdKey: String): List[FileRow] = {
    val convertHeaders: (String, String) => (String, String) = { 
      case (originalHeader, value) => 
        (SchemaUtils.convertToAlternateKey(outputHeaderKey, SchemaUtils.convertToValidationKey(inputHeaderKey, originalHeader)), value)
    }
    val reader = CSVReader.open(filePath)
    val all: Seq[Map[String, String]] = reader.allWithHeaders().map(_.map({ case (k,v) => convertHeaders(k,v) }))
    all.map { row => 
      FileRow(
        matchIdentifier = row(SchemaUtils.convertToAlternateKey(outputHeaderKey, uniqueAssetIdKey)), 
        metadata = row.collect { 
          case (columnHeader, value) if columnHeader.nonEmpty => Metadata(columnHeader, value)}.toList
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

@deprecated
case class FileData(allRowsWithHeader: List[List[String]], fileRows: List[FileRow])
