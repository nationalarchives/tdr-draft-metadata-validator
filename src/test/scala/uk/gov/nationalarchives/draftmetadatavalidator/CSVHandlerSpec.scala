package uk.gov.nationalarchives.draftmetadatavalidator

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.nio.file.{Files, Path}

class CSVHandlerSpec extends AnyFlatSpec with BeforeAndAfterEach {

  val filePath: String = getClass.getResource("/sample-for-csv-handler.csv").getPath
  val fileWithReversedColumnOrderPath: String = getClass.getResource("/sample-for-csv-handler-columns-reversed.csv").getPath
  val fileWithUnsupportedColumnPath: String = getClass.getResource("/sample-for-csv-handler-unsupported-column.csv").getPath
  val metadataNames: List[String] = List("Filename", "Filepath", "end_date", "ClosureStatus", "ClosurePeriod")

  "loadCSV with output alternate key of tdrDataLoadHeader" should "read the file and return expected FileRows with correct names for DB persistence" in {
    val fileData: Seq[FileRow] = CSVHandler.loadCSV(filePath, "tdrFileHeader", "tdrDataLoadHeader", "file_path")

    val expected = List(
      FileRow(
        "aa/file.jpg",
        List(
          Metadata("ClientSideOriginalFilepath", "aa/file.jpg"),
          Metadata("ClientSideFileLastModifiedDate", "2020-05-29"),
          Metadata("Filename", "file1.jpg"),
          Metadata("ClosurePeriod", "10"),
          Metadata("ClosureType", "Closed")
        )
      ),
      FileRow(
        "aa/file.jpg",
        List(
          Metadata("ClientSideOriginalFilepath", "aa/file.jpg"),
          Metadata("ClientSideFileLastModifiedDate", "2020-05-29"),
          Metadata("Filename", "file2.jpg"),
          Metadata("ClosurePeriod", ""),
          Metadata("ClosureType", "Open")
        )
      ),
      FileRow(
        "aa/file.jpg",
        List(
          Metadata("ClientSideOriginalFilepath", "aa/file.jpg"),
          Metadata("ClientSideFileLastModifiedDate", "2020-05-29"),
          Metadata("Filename", "file3.jpg"),
          Metadata("ClosurePeriod", ""),
          Metadata("ClosureType", "Open")
        )
      )
    )
    fileData should be(expected)
  }

  "loadCSV with matching input and output header keys" should "read the file and return expected FileRows using names from the input file headers" in {
    val fileRows = CSVHandler.loadCSV(filePath, "tdrFileHeader", "tdrFileHeader", "file_path")

    val expected = List(
      FileRow(
        "aa/file.jpg",
        List(
          Metadata("Closure status", "Closed"),
          Metadata("Closure Period", "10"),
          Metadata("Filename", "file1.jpg"),
          Metadata("Date last modified", "2020-05-29"),
          Metadata("Filepath", "aa/file.jpg")
        )
      ),
      FileRow(
        "aa/file.jpg",
        List(
          Metadata("Closure status", "Open"),
          Metadata("Closure Period", ""),
          Metadata("Filename", "file2.jpg"),
          Metadata("Date last modified", "2020-05-29"),
          Metadata("Filepath", "aa/file.jpg")
        )
      ),
      FileRow(
        "aa/file.jpg",
        List(
          Metadata("Closure status", "Open"),
          Metadata("Closure Period", ""),
          Metadata("Filename", "file3.jpg"),
          Metadata("Date last modified", "2020-05-29"),
          Metadata("Filepath", "aa/file.jpg")
        )
      )
    )

    fileRows should be(expected)
  }

  "loadCSV output" should "return expected values regardless of column order" in {
    val originalFileData: Seq[FileRow] = CSVHandler.loadCSV(filePath, "tdrFileHeader", "tdrDataLoadHeader", "file_path")
    val reversedColumnFileData: Seq[FileRow] = CSVHandler.loadCSV(fileWithReversedColumnOrderPath, "tdrFileHeader", "tdrDataLoadHeader", "file_path")
    val expected = List(
      FileRow(
        "aa/file.jpg",
        List(
          Metadata("ClientSideOriginalFilepath", "aa/file.jpg"),
          Metadata("ClientSideFileLastModifiedDate", "2020-05-29"),
          Metadata("Filename", "file1.jpg"),
          Metadata("ClosurePeriod", "10"),
          Metadata("ClosureType", "Closed")
        )
      ),
      FileRow(
        "aa/file.jpg",
        List(
          Metadata("ClientSideOriginalFilepath", "aa/file.jpg"),
          Metadata("ClientSideFileLastModifiedDate", "2020-05-29"),
          Metadata("Filename", "file2.jpg"),
          Metadata("ClosurePeriod", ""),
          Metadata("ClosureType", "Open")
        )
      ),
      FileRow(
        "aa/file.jpg",
        List(
          Metadata("ClientSideOriginalFilepath", "aa/file.jpg"),
          Metadata("ClientSideFileLastModifiedDate", "2020-05-29"),
          Metadata("Filename", "file3.jpg"),
          Metadata("ClosurePeriod", ""),
          Metadata("ClosureType", "Open")
        )
      )
    )
    originalFileData shouldBe expected
    reversedColumnFileData shouldBe expected
  }

  // TODO: We may want to throw an error in this case in future to avoid valid data silently being ignored due to column name typos
  "loadCSV output" should "ignore unsupported columns" in {
    val fileData: Seq[FileRow] = CSVHandler.loadCSV(fileWithUnsupportedColumnPath, "tdrFileHeader", "tdrDataLoadHeader", "file_path")
    val expected = List(
      FileRow(
        "aa/file.jpg",
        List(
          Metadata("ClientSideOriginalFilepath", "aa/file.jpg"),
          Metadata("ClientSideFileLastModifiedDate", "2020-05-29"),
          Metadata("Filename", "file1.jpg"),
          Metadata("ClosurePeriod", "10"),
          Metadata("ClosureType", "Closed")
        )
      ),
      FileRow(
        "aa/file.jpg",
        List(
          Metadata("ClientSideOriginalFilepath", "aa/file.jpg"),
          Metadata("ClientSideFileLastModifiedDate", "2020-05-29"),
          Metadata("Filename", "file2.jpg"),
          Metadata("ClosurePeriod", ""),
          Metadata("ClosureType", "Open")
        )
      ),
      FileRow(
        "aa/file.jpg",
        List(
          Metadata("ClientSideOriginalFilepath", "aa/file.jpg"),
          Metadata("ClientSideFileLastModifiedDate", "2020-05-29"),
          Metadata("Filename", "file3.jpg"),
          Metadata("ClosurePeriod", ""),
          Metadata("ClosureType", "Open")
        )
      )
    )
    fileData shouldBe expected
  }

  "writeCsv" should "read the file and return FileData with all the rows" in {
    val metadata = List(
      List("file1.jpg", "Open", "10"),
      List("file2.jpg", "Open", ""),
      List("file3.jpg", "Open", "")
    )
    CSVHandler.writeCsv(metadata, getClass.getResource("/").getPath + "updated.csv")

    val expected =
      """file1.jpg,Open,10
      |file2.jpg,Open,
      |file3.jpg,Open,
      |""".stripMargin
    val actual = Files.readString(Path.of(getClass.getResource("/updated.csv").toURI)).replace("\r", "")
    actual should be(expected)
  }
}
