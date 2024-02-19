package uk.gov.nationalarchives.draftmetadatavalidator

import com.github.tototoshi.csv.{CSVReader, CSVWriter}
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Paths}

class CSVHandler {

  def loadCSV(filePath: String, metadataNames: List[String]): FileData = {
    val reader = CSVReader.open(filePath)
    val allRowsWithHeader = reader.all()
    val fileRows = allRowsWithHeader match {
      case _ :: rows =>
        rows.map { case fileName :: data =>
          FileRow(
            fileName,
            metadataNames.zipWithIndex.map { case (name, index) => Metadata(name, data(index)) }
          )
        }
    }
    FileData(allRowsWithHeader.head, fileRows)
  }

  def writeCsv(rows: List[List[String]], filePath: String): Unit = {
    val bas = new ByteArrayOutputStream()
    val writer = CSVWriter.open(bas)
    writer.writeAll(rows)
    Files.writeString(Paths.get(filePath), bas.toString("UTF-8"))
  }
}

case class FileData(header: List[String], fileRows: List[FileRow])
