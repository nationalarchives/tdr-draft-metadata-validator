package uk.gov.nationalarchives.draftmetadatavalidator

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.jdk.CollectionConverters.MapHasAsJava

object LambdaRunner extends App {
  private val body =
    """{
      |  "consignmentId": "f82af3bf-b742-454c-9771-bfd6c5eae749"
      |}
      |""".stripMargin

//  new Lambda().handleRequest(baos, output)
//  val res = output.toByteArray.map(_.toChar).mkString
  val queryParams = Map("consignmentId" -> "f82af3bf-b742-454c-9771-bfd6c5eae749").asJava
  val event = new APIGatewayProxyRequestEvent()
  event.setQueryStringParameters(queryParams)
  new Lambda().handleRequest(event, null)
}
