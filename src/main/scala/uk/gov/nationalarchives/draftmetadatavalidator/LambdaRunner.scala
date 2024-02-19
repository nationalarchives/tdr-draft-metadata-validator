package uk.gov.nationalarchives.draftmetadatavalidator

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

object LambdaRunner extends App {
  private val body =
    """{
      |  "consignmentId": "f82af3bf-b742-454c-9771-bfd6c5eae749"
      |}
      |""".stripMargin

  private val baos = new ByteArrayInputStream(body.getBytes())
  val output = new ByteArrayOutputStream()
  new Lambda().handleRequest(baos, output)
  val res = output.toByteArray.map(_.toChar).mkString
  println(res)
}
