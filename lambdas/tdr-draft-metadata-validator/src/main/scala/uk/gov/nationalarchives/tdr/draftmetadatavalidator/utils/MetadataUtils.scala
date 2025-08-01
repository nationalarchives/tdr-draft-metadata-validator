package uk.gov.nationalarchives.tdr.draftmetadatavalidator.utils

import graphql.codegen.GetCustomMetadata.customMetadata.CustomMetadata
import graphql.codegen.types.DataType.{Boolean, DateTime, Text}
import graphql.codegen.types.{AddOrUpdateFileMetadata, AddOrUpdateMetadata}
import uk.gov.nationalarchives.tdr.draftmetadatavalidator.FileDetail
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.sql.Timestamp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object MetadataUtils {

  val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

  def filterProtectedFields(customMetadata: List[CustomMetadata], fileRows: List[FileRow], filesWithUniqueAssetIdKey: Map[String, FileDetail]): List[AddOrUpdateFileMetadata] = {
    val filterProtectedMetadata = customMetadata.filter(!_.editable).map(_.name)
    val updatedFileRows = fileRows.map { fileMetadata =>
      val filteredMetadata = fileMetadata.metadata.filterNot(metadata => filterProtectedMetadata.contains(metadata.name))
      fileMetadata.copy(metadata = filteredMetadata)
    }
    convertDataToBulkFileMetadataInput(updatedFileRows, customMetadata, filesWithUniqueAssetIdKey)
  }

  private def convertDataToBulkFileMetadataInput(
      fileRows: List[FileRow],
      customMetadata: List[CustomMetadata],
      filesWithUniqueAssetIdKey: Map[String, FileDetail]
  ): List[AddOrUpdateFileMetadata] = {
    fileRows.map { fileRow =>
      val fileId = filesWithUniqueAssetIdKey
        .getOrElse(fileRow.matchIdentifier, throw new RuntimeException("Unexpected state: db identifier unavailable"))
        .fileId
      AddOrUpdateFileMetadata(
        fileId,
        fileRow.metadata.flatMap {
          case m if m.value.nonEmpty =>
            customMetadata.find(_.name == m.name).map(cm => createAddOrUpdateMetadata(m, cm)).getOrElse(List.empty)
          case m => List(AddOrUpdateMetadata(m.name, ""))
        }
      )
    }
  }

  private def createAddOrUpdateMetadata(metadata: Metadata, customMetadata: CustomMetadata): List[AddOrUpdateMetadata] = {
    val values = customMetadata.dataType match {
      case DateTime => Timestamp.valueOf(LocalDate.parse(metadata.value, dateTimeFormatter).atStartOfDay()).toString :: Nil
      case Boolean =>
        metadata.value.toLowerCase() match {
          case "yes" => "true" :: Nil
          case _     => "false" :: Nil
        }
      case _ => metadata.value :: Nil
    }
    values.map(v => AddOrUpdateMetadata(metadata.name, v))
  }

}
