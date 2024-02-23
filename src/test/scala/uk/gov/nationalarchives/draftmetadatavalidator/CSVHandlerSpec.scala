package uk.gov.nationalarchives.draftmetadatavalidator

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.nio.file.{Files, Path}

class CSVHandlerSpec extends AnyFlatSpec with BeforeAndAfterEach {

  val filePath: String = getClass.getResource("/sample.csv").getPath
  val metadataNames: List[String] = List("ClosureStatus", "ClosurePeriod")

  "loadCSV" should "read the file and return FileData with all the rows" in {
    val csvHandler = new CSVHandler
    val fileData = csvHandler.loadCSV(filePath, metadataNames)

    val expected = FileData(
      List("Filename", "Closure status", "Closure Period"),
      List(
        FileRow("file1.jpg", List(Metadata("ClosureStatus", "Open"), Metadata("ClosurePeriod", "10"))),
        FileRow("file2.jpg", List(Metadata("ClosureStatus", "Open"), Metadata("ClosurePeriod", ""))),
        FileRow("file3.jpg", List(Metadata("ClosureStatus", "Open"), Metadata("ClosurePeriod", "")))
      )
    )

    fileData should be(expected)
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
