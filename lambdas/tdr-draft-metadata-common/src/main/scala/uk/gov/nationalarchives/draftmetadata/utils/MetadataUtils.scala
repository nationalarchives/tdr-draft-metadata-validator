package uk.gov.nationalarchives.draftmetadata.utils

import graphql.codegen.GetCustomMetadata.customMetadata.CustomMetadata
import graphql.codegen.types.DataType.{Boolean, DateTime}
import graphql.codegen.types.{AddOrUpdateFileMetadata, AddOrUpdateMetadata}
import uk.gov.nationalarchives.draftmetadata.{FileRow, Metadata}

import java.sql.Timestamp
import java.time.LocalDate
import java.time.format.DateTimeFormatter


object MetadataUtils {

  val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

  /**
   * Filters out protected metadata fields and converts file rows to bulk file metadata input format.
   *
   * @param customMetadata List of custom metadata fields
   * @param fileRows List of file rows with their metadata
   * @param filesWithUniqueAssetIdKey Map from file identifiers to file details
   * @param fileIdExtractor Function to extract the UUID from the file detail object
   * @tparam F The type of file detail object
   * @return List of AddOrUpdateFileMetadata objects ready for database operations
   */
  def filterProtectedFields[F](
    customMetadata: List[CustomMetadata],
    fileRows: List[FileRow],
    filesWithUniqueAssetIdKey: Map[String, F]
  )(fileIdExtractor: F => java.util.UUID): List[AddOrUpdateFileMetadata] = {
    val filterProtectedMetadata = customMetadata.filter(!_.editable).map(_.name)
    val updatedFileRows = fileRows.map { fileMetadata =>
      val filteredMetadata = fileMetadata.metadata.filterNot(metadata => filterProtectedMetadata.contains(metadata.name))
      fileMetadata.copy(metadata = filteredMetadata)
    }
    convertDataToBulkFileMetadataInput(updatedFileRows, customMetadata, filesWithUniqueAssetIdKey)(fileIdExtractor)
  }

  /**
   * Converts file rows data to bulk file metadata input format.
   */
  private def convertDataToBulkFileMetadataInput[F](
      fileRows: List[FileRow],
      customMetadata: List[CustomMetadata],
      filesWithUniqueAssetIdKey: Map[String, F]
  )(fileIdExtractor: F => java.util.UUID): List[AddOrUpdateFileMetadata] = {
    fileRows.map { fileRow =>
      val fileDetail = filesWithUniqueAssetIdKey
        .getOrElse(fileRow.matchIdentifier, throw new RuntimeException("Unexpected state: db identifier unavailable"))
      val fileId = fileIdExtractor(fileDetail)

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

  /**
   * Creates AddOrUpdateMetadata objects from a metadata item and its definition.
   */
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
