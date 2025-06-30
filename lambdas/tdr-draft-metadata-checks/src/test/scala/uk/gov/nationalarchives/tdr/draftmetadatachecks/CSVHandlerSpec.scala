package uk.gov.nationalarchives.tdr.draftmetadatachecks

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import uk.gov.nationalarchives.tdr.schemautils.ConfigUtils
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.nio.file.{Files, Path}

class CSVHandlerSpec extends AnyFlatSpec with BeforeAndAfterEach {

  val filePath: String = getClass.getResource("/sample-for-csv-handler.csv").getPath
  val metadataNames: List[String] = List("Filename", "Filepath", "end_date", "ClosureStatus", "ClosurePeriod")
  implicit val metadataConfiguration: ConfigUtils.MetadataConfiguration = ConfigUtils.loadConfiguration

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
