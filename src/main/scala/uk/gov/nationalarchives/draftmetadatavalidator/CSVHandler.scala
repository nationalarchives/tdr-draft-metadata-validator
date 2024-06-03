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
        rows.map { case filename :: filepath :: dateLastModified :: data =>
          FileRow(
            data.last,
            metadataNames.zipWithIndex.map { case (name, index) => Metadata(name, data(index)) }
          )
        }
    }
    FileData(allRowsWithHeader, fileRows)
  }

  /** Reads a CSV file into a list of FileRows The FileRow.fileName is the identifier and has been used to store the UUID in above, expecting the UUID to be in the last column.
    * What the identifier is, is TBD with the metadata key(header) unaltered and the value as a String
    * @param filePath
    *   path to csv
    * @return
    *   List of FileRows
    */
  def loadCSV(filePath: String): List[FileRow] = {
    val reader = CSVReader.open(filePath)
    val all: Seq[Map[String, String]] = reader.allWithHeaders()
    val fileRows = all.map(metadataMap => FileRow(metadataMap("UUID"), metadataMap.map(b => Metadata(b._1, b._2)).toList))
    fileRows.toList
  }

  def writeCsv(rows: List[List[String]], filePath: String): Unit = {
    val bas = new ByteArrayOutputStream()
    val writer = CSVWriter.open(bas)
    writer.writeAll(rows)
    Files.writeString(Paths.get(filePath), bas.toString("UTF-8"))
  }
}

case class FileData(allRowsWithHeader: List[List[String]], fileRows: List[FileRow])
