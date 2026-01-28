package uk.gov.nationalarchives.tdr.draftmetadatachecks

import scala.jdk.CollectionConverters.MapHasAsJava

object LambdaRunner extends App {
  val input = Map("consignmentId" -> "4009eaad-dc23-418a-a01c-9aba26b4b061".asInstanceOf[Object]).asJava
  println(new Lambda().handleRequest(input, null))
}
