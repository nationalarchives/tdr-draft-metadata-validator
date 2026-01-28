package uk.gov.nationalarchives.tdr.draftmetadatachecks

import scala.jdk.CollectionConverters.MapHasAsJava

object LambdaRunner extends App {
  val input = Map("consignmentId" -> "03487bf0-07f8-45e2-9ce8-2a5642b86aaa".asInstanceOf[Object]).asJava
  println(new Lambda().handleRequest(input, null))
}
