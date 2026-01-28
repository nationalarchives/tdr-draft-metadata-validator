package uk.gov.nationalarchives.tdr.draftmetadatapersistence

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent

import scala.jdk.CollectionConverters.MapHasAsJava

object LambdaRunner extends App {
  val consignmentId = if (args.length > 0) args(0) else "4009eaad-dc23-418a-a01c-9aba26b4b061"
  val input = Map("consignmentId" -> consignmentId.asInstanceOf[Object]).asJava
  println(new Lambda().handleRequest(input, null))
}
