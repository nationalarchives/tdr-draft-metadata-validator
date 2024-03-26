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
      List(
        List("Filename", "Filepath", "Date last modified", "Closure status", "Closure Period", "UUID"),
        List("file1.jpg", "aa/file.jpg", "2020-05-29", "Closed", "10", "16b2f65c-ec50-494b-824b-f8c08e6b575c"),
        List("file2.jpg", "aa/file.jpg", "2020-05-29", "Open", "", "18449d9b-6a86-40b4-8855-b872a79bebad"),
        List("file3.jpg", "aa/file.jpg", "2020-05-29", "Open", "", "61b49923-daf7-4140-98f1-58ba6cbed61f")
      ),
      List(
        FileRow("16b2f65c-ec50-494b-824b-f8c08e6b575c", List(Metadata("ClosureStatus", "Closed"), Metadata("ClosurePeriod", "10"))),
        FileRow("18449d9b-6a86-40b4-8855-b872a79bebad", List(Metadata("ClosureStatus", "Open"), Metadata("ClosurePeriod", ""))),
        FileRow("61b49923-daf7-4140-98f1-58ba6cbed61f", List(Metadata("ClosureStatus", "Open"), Metadata("ClosurePeriod", "")))
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
