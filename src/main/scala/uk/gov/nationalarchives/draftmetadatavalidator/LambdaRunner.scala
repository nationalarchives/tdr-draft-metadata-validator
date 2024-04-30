package uk.gov.nationalarchives.draftmetadatavalidator

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent

import scala.jdk.CollectionConverters.MapHasAsJava

object LambdaRunner extends App {
  val pathParams = Map("consignmentId" -> "f82af3bf-b742-454c-9771-bfd6c5eae749").asJava
  val event = new APIGatewayProxyRequestEvent()
  event.setPathParameters(pathParams)
  // new Lambda().handleRequest(event, null)
}
