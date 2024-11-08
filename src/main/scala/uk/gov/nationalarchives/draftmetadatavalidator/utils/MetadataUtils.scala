package uk.gov.nationalarchives.draftmetadatavalidator.utils

import graphql.codegen.GetCustomMetadata.customMetadata.CustomMetadata
import graphql.codegen.types.DataType.{Boolean, DateTime, Text}
import graphql.codegen.types.{AddOrUpdateFileMetadata, AddOrUpdateMetadata}
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.sql.Timestamp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

object MetadataUtils {

  def filterProtectedFields(customMetadata: List[CustomMetadata], fileRows: List[FileRow], clientIdToPersistenceId: Map[String, Option[UUID]]): List[AddOrUpdateFileMetadata] = {
    val filterProtectedMetadata = customMetadata.filter(!_.editable).map(_.name)
    val updatedFileRows = fileRows.map { fileMetadata =>
      val filteredMetadata = fileMetadata.metadata.filterNot(metadata => filterProtectedMetadata.contains(metadata.name))
      fileMetadata.copy(metadata = filteredMetadata)
    }
    convertDataToBulkFileMetadataInput(updatedFileRows, customMetadata, clientIdToPersistenceId: Map[String, Option[UUID]])
  }

  private def convertDataToBulkFileMetadataInput(fileRows: List[FileRow], customMetadata: List[CustomMetadata], clientIdToPersistenceId: Map[String, Option[UUID]]): List[AddOrUpdateFileMetadata] = {
    fileRows.collect { case fileRow =>
      AddOrUpdateFileMetadata(
        UUID.fromString(fileRow.matchIdentifier),
        fileRow.metadata.collect {
          case m if m.value.nonEmpty => createAddOrUpdateMetadata(m, customMetadata.find(_.name == m.name).get)
          case m                     => List(AddOrUpdateMetadata(m.name, ""))
        }.flatten
      )
    }
  }

  private def createAddOrUpdateMetadata(metadata: Metadata, customMetadata: CustomMetadata): List[AddOrUpdateMetadata] = {
    val format = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val values = customMetadata.dataType match {
      case DateTime => Timestamp.valueOf(LocalDate.parse(metadata.value, format).atStartOfDay()).toString :: Nil
      case Boolean =>
        metadata.value.toLowerCase() match {
          case "yes" => "true" :: Nil
          case _     => "false" :: Nil
        }
      case Text if customMetadata.multiValue => metadata.value.split("\\|").toList
      case _                                 => metadata.value :: Nil
    }
    values.map(v => AddOrUpdateMetadata(metadata.name, v))
  }

}
