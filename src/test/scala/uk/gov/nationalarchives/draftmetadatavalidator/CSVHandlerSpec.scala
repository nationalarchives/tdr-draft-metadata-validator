package uk.gov.nationalarchives.draftmetadatavalidator

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.nio.file.{Files, Path}

class CSVHandlerSpec extends AnyFlatSpec with BeforeAndAfterEach {

  val filePath: String = getClass.getResource("/sample-for-csv-handler.csv").getPath
  val metadataNames: List[String] = List("Filename", "Filepath", "end_date", "ClosureStatus", "ClosurePeriod")

  "loadCSV with path and metadata names" should "read the file and return FileData with all the rows" in {
    val csvHandler = new CSVHandler
    val fileData = csvHandler.loadCSV(filePath, metadataNames, "Filepath")

    val expected = FileData(
      List(
        List("Filename", "Filepath", "Date last modified", "Closure status", "Closure Period"),
        List("file1.jpg", "aa/file1.jpg", "2020-05-29", "Closed", "10"),
        List("file2.jpg", "bb/file2.jpg", "2020-05-29", "Open", ""),
        List("file3.jpg", "cc/file3.jpg", "2020-05-29", "Open", "")
      ),
      List(
        FileRow(
          "aa/file1.jpg",
          List(Metadata("Filename", "file1.jpg"), Metadata("Filepath", "aa/file1.jpg"), Metadata("end_date", "2020-05-29"), Metadata("ClosureStatus", "Closed"))
        ),
        FileRow(
          "bb/file2.jpg",
          List(Metadata("Filename", "file2.jpg"), Metadata("Filepath", "bb/file2.jpg"), Metadata("end_date", "2020-05-29"), Metadata("ClosureStatus", "Open"))
        ),
        FileRow(
          "cc/file3.jpg",
          List(Metadata("Filename", "file3.jpg"), Metadata("Filepath", "cc/file3.jpg"), Metadata("end_date", "2020-05-29"), Metadata("ClosureStatus", "Open"))
        )
      )
    )
    fileData should be(expected)
  }

  "loadCSV with path " should "read the file and return FileRows" in {
    val csvHandler = new CSVHandler
    val fileRows = csvHandler.loadCSV(filePath, "Filepath")

    val expected = List(
      FileRow(
        "aa/file1.jpg",
        List(
          Metadata("Closure status", "Closed"),
          Metadata("Closure Period", "10"),
          Metadata("Filename", "file1.jpg"),
          Metadata("Date last modified", "2020-05-29"),
          Metadata("Filepath", "aa/file1.jpg")
        )
      ),
      FileRow(
        "bb/file2.jpg",
        List(
          Metadata("Closure status", "Open"),
          Metadata("Closure Period", ""),
          Metadata("Filename", "file2.jpg"),
          Metadata("Date last modified", "2020-05-29"),
          Metadata("Filepath", "bb/file2.jpg")
        )
      ),
      FileRow(
        "cc/file3.jpg",
        List(
          Metadata("Closure status", "Open"),
          Metadata("Closure Period", ""),
          Metadata("Filename", "file3.jpg"),
          Metadata("Date last modified", "2020-05-29"),
          Metadata("Filepath", "cc/file3.jpg")
        )
      )
    )

    fileRows should be(expected)
  }

  "writeCsv" should "read the file and return FileData with all the rows" in {
    val csvHandler = new CSVHandler

    val metadata = List(
      List("file1.jpg", "Open", "10"),
      List("file2.jpg", "Open", ""),
      List("file3.jpg", "Open", "")
    )
    csvHandler.writeCsv(metadata, getClass.getResource("/").getPath + "updated.csv")

    val expected =
      """file1.jpg,Open,10
      |file2.jpg,Open,
      |file3.jpg,Open,
      |""".stripMargin
    val actual = Files.readString(Path.of(getClass.getResource("/updated.csv").toURI)).replace("\r", "")
    actual should be(expected)
  }
}
