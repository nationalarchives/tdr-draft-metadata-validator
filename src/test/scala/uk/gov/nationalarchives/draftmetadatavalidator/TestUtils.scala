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

  def filesWithUniquesAssetIdKeyResponse(fileTestData: List[FileTestData]): String = {
    val getFilesData = fileTestData
      .map(data => s"""
             |{
             |   "fileId": "${data.fileId}",
             |   "fileName": "${data.fileName}",
             |   "metadata":
             |     {
             |       "clientSideOriginalFilePath": "${data.filePath}",
             |       "clientSideLastModifiedDate": "${data.lastModifiedDate}"
             |     }
             |}
             |""".stripMargin)
      .mkString(",\n")

    s"""{
      "data": {
        "getConsignment": {
          "files": [$getFilesData]
        }
      }
    }"""
  }

  val fileTestData: List[FileTestData] = List(
    FileTestData(UUID.randomUUID(), "test3.txt", "test/test3.txt", "2024-03-26T16:00"),
    FileTestData(UUID.randomUUID(), "test1.txt", "test/test1.txt", "2024-03-26T16:00"),
    FileTestData(UUID.randomUUID(), "test2.txt", "test/test2.txt", "2024-03-26T16:00")
  )
}

case class FileTestData(fileId: UUID, fileName: String, filePath: String, lastModifiedDate: String)
