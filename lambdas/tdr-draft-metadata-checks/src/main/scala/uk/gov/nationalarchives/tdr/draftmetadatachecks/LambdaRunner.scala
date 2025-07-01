package uk.gov.nationalarchives.tdr.draftmetadatachecks

import scala.jdk.CollectionConverters.MapHasAsJava

object LambdaRunner extends App {
  val input = Map("consignmentId" -> "6924295f-15f7-4c73-95ee-3ab1eeb5c91c".asInstanceOf[Object]).asJava
  new Lambda().handleRequest(input, null)
}
