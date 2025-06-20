package uk.gov.nationalarchives.tdr.draftmetadatapersistence

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent

import scala.jdk.CollectionConverters.MapHasAsJava

object LambdaRunner extends App {
  val consignmentId = if (args.length > 0) args(0) else "665a26d6-9b7a-450c-904e-169517c4c475"
  val input = Map("consignmentId" -> consignmentId.asInstanceOf[Object]).asJava
  println(new Lambda().handleRequest(input, null))
}
