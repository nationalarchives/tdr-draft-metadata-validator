package uk.gov.nationalarchives.draftmetadatavalidator

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent

import scala.jdk.CollectionConverters.MapHasAsJava

object LambdaRunner extends App {
  val input = Map("consignmentId" -> "f82af3bf-b742-454c-9771-bfd6c5eae749".asInstanceOf[Object]).asJava
  new Lambda().handleRequest(input, null)
}
