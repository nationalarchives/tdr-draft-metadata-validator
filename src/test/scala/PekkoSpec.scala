import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.IOResult
import org.apache.pekko.stream.connectors.csv.scaladsl.{CsvParsing, CsvToMap}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

import scala.concurrent.Future
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.nio.file.Paths

class PekkoSpec extends AnyFlatSpec {

  implicit val actorSystem: ActorSystem[Nothing] = ActorSystem[Nothing](Behaviors.empty, "pekko-connectors-samples")

  import actorSystem.executionContext

  val input =
    """Filename,Closure status,Closure Start Date,Closure Period,FOI exemption code,FOI decision asserted,Is the title sensitive for the public?,Add alternative title without the file extension,Description,Is the description sensitive for the public?,Alternative description,Language,Date of the record,Translated title of record,Former reference
      |rnd_9.jpg,Open,,,,,,,,No,,English,,,
      |rnd_875.jpg,Open,,,,,No,,,No,,English,,,
      |rnd_789.jpg,Closed,,,,,No,,,No,,English,,,
      |rnd_297.jpg,Open,,,,,No,,,No,,English,,,""".stripMargin // also should complete once notices end of array

  val output =
    """Filename,Closure status,Closure Start Date,Closure Period,FOI exemption code,FOI decision asserted,Is the title sensitive for the public?,Add alternative title without the file extension,Description,Is the description sensitive for the public?,Alternative description,Language,Date of the record,Translated title of record,Former reference,Error
      |rnd_9.jpg,Open,,,,,,,,No,,English,,,,ClosureType: CLOSURE_METADATA_EXISTS_WHEN_FILE_IS_OPEN
      |rnd_875.jpg,Open,,,,,No,,,No,,English,,,,
      |rnd_789.jpg,Closed,,,,,No,,,No,,English,,,,ClosurePeriod: EMPTY_VALUE_ERROR | FoiExemptionCode: EMPTY_VALUE_ERROR | FoiExemptionAsserted: EMPTY_VALUE_ERROR
      |rnd_297.jpg,Open,,,,,No,,,No,,English,,,,""".stripMargin // also should complete once notices end of array
  import org.apache.pekko.stream.scaladsl._
  val file = Paths.get("/home/ian/Downloads/TDR-2024-HNB.csv")
 private val future =
    FileIO.fromPath(file)
      .via(CsvParsing.lineScanner())
      .via(CsvToMap.toMapAsStrings())
      .map(mapToLineRow)
      .runWith(Sink.foreach(println))

  future.onComplete { a =>
    println(a)
    actorSystem.terminate()
  }



  val foreach: Future[IOResult] = FileIO.fromPath(file).to(Sink.ignore).run()
  private def mapToLineRow (input: Map[String, String]) = {
     FileRow(input("Filename"),input.keys.map{ a => Metadata(a,input(a)) }.toList)
  }

  "Pekko" should "be Ok" in {
   "good" shouldBe "good"
  }
}
