package uk.gov.nationalarchives.draftmetadatavalidator

import graphql.codegen.GetCustomMetadata.customMetadata.CustomMetadata
import graphql.codegen.types.DataType
import graphql.codegen.types.DataType.Text
import graphql.codegen.types.PropertyType.Defined

import java.util.UUID

object TestUtils {

  def createCustomMetadata(name: String, fullName: String, exportOrdinal: Int, dataType: DataType = Text, allowExport: Boolean = true, editable: Boolean = true): CustomMetadata =
    CustomMetadata(
      name,
      None,
      Some(fullName),
      Defined,
      Some("MandatoryClosure"),
      dataType,
      editable,
      multiValue = false,
      Some("Open"),
      1,
      Nil,
      Option(exportOrdinal),
      allowExport = allowExport
    )

  def testFileIdMetadata(clientIds: Seq[String]): Seq[TestFileIdMetadata] =
    clientIds.map(id => TestFileIdMetadata(UUID.randomUUID(), "ClientSideOriginalFilepath", id))
}

case class TestFileIdMetadata(fileId: UUID, persistedIdHeader: String, clientId: String) {
  val asJson: String =
    s"""{
       |  "fileId": "$fileId",
       |  "fileName": "$clientId",
       |  "fileMetadata": [
       |    {
       |      "name": "$persistedIdHeader",
       |      "value": "$clientId"
       |    },
       |    {
       |      "name": "FileType",
       |      "value": "File"
       |    }
       |  ],
       |  "fileStatuses": []
       |}""".stripMargin
}
