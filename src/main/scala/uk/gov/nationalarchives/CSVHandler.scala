package uk.gov.nationalarchives

import com.github.tototoshi.csv.{CSVReader, CSVWriter}
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Paths}

class CSVHandler {

  def loadCSV(fileName: String): List[FileRow] = {
    val reader = CSVReader.open(s"/tmp/$fileName")
    val allRowsWithHeader = reader.all()
    allRowsWithHeader match {
      case _ :: tail =>
        tail.map(row => {
          FileRow(
            row.head,
            List(
              Metadata("ClosureType", row(1)),
              Metadata("ClosureStartDate", row(2)),
              Metadata("ClosurePeriod", row(3)),
              Metadata("FoiExemptionCode", row(4)),
              Metadata("FoiExemptionAsserted", row(5)),
              Metadata("TitleClosed", row(6)),
              Metadata("TitleAlternate", row(7)),
              Metadata("description", row(8)),
              Metadata("DescriptionClosed", row(9)),
              Metadata("DescriptionAlternate", row(10)),
              Metadata("Language", row(11)),
              Metadata("end_date", row(12)),
              Metadata("file_name_translation", row(13)),
              Metadata("former_reference_department", row(14))
            )
          )
        })
    }
  }

  def writeCsv(rows: List[List[String]], fileName: String): Unit = {
    val bas = new ByteArrayOutputStream()
    val writer = CSVWriter.open(bas)
    writer.writeAll(rows)

    Files.writeString(Paths.get(s"/tmp/$fileName"), bas.toString("UTF-8"))

  }
}
