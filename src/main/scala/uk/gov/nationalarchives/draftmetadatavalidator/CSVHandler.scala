package uk.gov.nationalarchives.draftmetadatavalidator

import com.github.tototoshi.csv.{CSVReader, CSVWriter}
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Paths}
import scala.io.Source

class CSVHandler {

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
  def loadCSV(filePath: String, uniqueRowKey: String): List[FileRow] = {
    val reader = CSVReader.open(filePath)
    val all: Seq[Map[String, String]] = reader.allWithHeaders()
    val fileRows = all.map(row => FileRow(row(uniqueRowKey), row.map(columnHeaderValue => Metadata(columnHeaderValue._1, columnHeaderValue._2)).toList))
    fileRows.toList
  }

  def loadHeaders(filePath: String): Option[String] = {
    val source = Source.fromFile(filePath)
    try {
      source.getLines.find(_ => true)
    } finally {
      source.close()
    }
  }

  def writeCsv(rows: List[List[String]], filePath: String): Unit = {
    val bas = new ByteArrayOutputStream()
    val writer = CSVWriter.open(bas)
    writer.writeAll(rows)
    Files.writeString(Paths.get(filePath), bas.toString("UTF-8"))
  }
}

case class FileData(allRowsWithHeader: List[List[String]], fileRows: List[FileRow])
