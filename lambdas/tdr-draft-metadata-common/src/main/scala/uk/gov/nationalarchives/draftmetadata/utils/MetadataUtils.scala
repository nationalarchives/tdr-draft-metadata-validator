package uk.gov.nationalarchives.draftmetadata.utils

import graphql.codegen.types.{AddOrUpdateFileMetadata, AddOrUpdateMetadata}
import uk.gov.nationalarchives.tdr.schemautils.ConfigUtils
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.sql.Timestamp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object MetadataUtils {

  val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
  private val config = ConfigUtils.loadConfiguration
  private val propertyTypeEvaluator = config.getPropertyType
  private val tdrDataLoadHeaderToPropertyMapper = config.propertyToOutputMapper("tdrDataLoadHeader")
  private val propertyToTdrDataLoadHeaderMapper = config.inputToPropertyMapper("tdrDataLoadHeader")
  private val systemProperties = config.getPropertiesByPropertyType("System")

  /** Filters out protected metadata fields and converts file rows to bulk file metadata input format.
    *
    * @param fileRows
    *   List of file rows with their metadata
    * @param filesWithUniqueAssetIdKey
    *   Map from file identifiers to file details
    * @param fileIdExtractor
    *   Function to extract the UUID from the file detail object
    * @tparam F
    *   The type of file detail object
    * @return
    *   List of AddOrUpdateFileMetadata objects ready for database operations
    */
  def filterProtectedFields[F](
      fileRows: List[FileRow],
      filesWithUniqueAssetIdKey: Map[String, F]
  )(fileIdExtractor: F => java.util.UUID): List[AddOrUpdateFileMetadata] = {
    val protectedMetadataProperties = systemProperties.map(p => tdrDataLoadHeaderToPropertyMapper(p))
    val updatedFileRows = fileRows.map { fileMetadata =>
      val filteredMetadata = fileMetadata.metadata.filterNot(metadata => protectedMetadataProperties.contains(metadata.name))
      fileMetadata.copy(metadata = filteredMetadata)
    }
    convertDataToBulkFileMetadataInput(updatedFileRows, filesWithUniqueAssetIdKey)(fileIdExtractor)
  }

  /** Converts file rows data to bulk file metadata input format.
    */
  private def convertDataToBulkFileMetadataInput[F](
      fileRows: List[FileRow],
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
            val propertyKey = propertyToTdrDataLoadHeaderMapper(m.name)
            createAddOrUpdateMetadata(m, propertyKey)
          case m => List(AddOrUpdateMetadata(m.name, ""))
        }
      )
    }
  }

  /** Creates AddOrUpdateMetadata objects from a metadata item and its definition.
    */
  private def createAddOrUpdateMetadata(metadata: Metadata, propertyKey: String): List[AddOrUpdateMetadata] = {
    val values = propertyTypeEvaluator(propertyKey) match {
      case t if t == "date" => Timestamp.valueOf(LocalDate.parse(metadata.value, dateTimeFormatter).atStartOfDay()).toString :: Nil
      case t if t == "boolean" =>
        metadata.value.toLowerCase() match {
          case "yes" => "true" :: Nil
          case _     => "false" :: Nil
        }
      case _ => metadata.value :: Nil
    }
    values.map(v => AddOrUpdateMetadata(metadata.name, v))
  }
}
