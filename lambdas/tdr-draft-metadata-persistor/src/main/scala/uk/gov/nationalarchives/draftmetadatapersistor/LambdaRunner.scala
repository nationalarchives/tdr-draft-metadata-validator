package uk.gov.nationalarchives.draftmetadatapersistor

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent

import scala.jdk.CollectionConverters.MapHasAsJava

object LambdaRunner extends App {
  val input = Map("consignmentId" -> "665a26d6-9b7a-450c-904e-169517c4c475".asInstanceOf[Object]).asJava
  new Lambda().handleRequest(input, null)
}
