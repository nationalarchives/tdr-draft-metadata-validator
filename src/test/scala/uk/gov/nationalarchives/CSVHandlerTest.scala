package uk.gov.nationalarchives

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec

class CSVHandlerTest extends AnyFlatSpec with BeforeAndAfterEach {


  "new test" should "fd" in {
    val csvHander = new CSVHandler
    val data = csvHander.loadCSV()


  }

}
