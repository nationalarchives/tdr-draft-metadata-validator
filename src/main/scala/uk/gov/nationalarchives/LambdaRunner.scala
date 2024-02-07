package uk.gov.nationalarchives

import com.amazonaws.services.lambda.runtime.Context

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.UUID

object LambdaRunner extends App {
  private val body =
    """{
      |  "consignmentId": "f82af3bf-b742-454c-9771-bfd6c5eae749"
      |}
      |""".stripMargin

//  "consignmentId": "53d748bd-e7b9-45c5-a63e-a2460073ac83"

  private val baos = new ByteArrayInputStream(body.getBytes())
  val output = new ByteArrayOutputStream()
  new Lambda().handleRequest(baos, output, null)
  val res = output.toByteArray.map(_.toChar).mkString
  println(res)
}
