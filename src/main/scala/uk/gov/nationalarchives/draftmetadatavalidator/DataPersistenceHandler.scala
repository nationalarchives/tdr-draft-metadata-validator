package uk.gov.nationalarchives.draftmetadatavalidator

import cats.effect.IO
import graphql.codegen.GetCustomMetadata.customMetadata.CustomMetadata
import graphql.codegen.types.DataType._
import graphql.codegen.types.{AddOrUpdateFileMetadata, AddOrUpdateMetadata, ClientSideMetadataInput}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import uk.gov.nationalarchives.draftmetadatavalidator.DataPersistenceHandler.MetadataInputs
import uk.gov.nationalarchives.draftmetadatavalidator.Lambda.DraftMetadata
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.sql.Timestamp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global

class DataPersistenceHandler(draftMetadata: DraftMetadata, clientSecret: String, graphQlApi: GraphQlApi) {

  implicit def logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  def persistValidMetadata(fileRows: List[FileRow], customMetadata: List[CustomMetadata]): IO[Boolean] = {
    val inputs = convertToInputs(fileRows, draftMetadata, customMetadata)
    for {
      _ <- graphQlApi.addOrUpdateBulkFileMetadata(draftMetadata.consignmentId, clientSecret, inputs.map(_.additionalMetadataInput))
      - <- graphQlApi.updateConsignmentStatus(draftMetadata.consignmentId, clientSecret, "DraftMetadata", "Completed")
    } yield false
  }

  private def convertToInputs(fileRows: List[FileRow], draftMetadata: DraftMetadata, customMetadata: List[CustomMetadata]) = {
    fileRows.collect { case fileRow =>
      val clientSideMetadataInput: Option[ClientSideMetadataInput] = if (draftMetadata.dataLoad) { Some(fileRow.toClientSideMetadataInput) }
      else None
      val additionalMetadataInput = fileRow.toAddOrUpdateFileMetadataInput(customMetadata)
      val undefinedMetadata = fileRow.toUndefinedMetadata(customMetadata)
      MetadataInputs(clientSideMetadataInput, additionalMetadataInput, undefinedMetadata)
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

  implicit class FileRowHelper(fileRow: FileRow) {
    def toAddOrUpdateFileMetadataInput(customMetadata: List[CustomMetadata]): AddOrUpdateFileMetadata = {
      AddOrUpdateFileMetadata(
        UUID.fromString(fileRow.fileName),
        fileRow.metadata.collect { case m if m.value.nonEmpty => createAddOrUpdateMetadata(m, customMetadata.find(_.name == m.name).get) }.flatten
      )
    }

    def toClientSideMetadataInput: ClientSideMetadataInput = {
      val metadata = fileRow.metadata
      ClientSideMetadataInput(metadata.originalPath, metadata.checksum, metadata.lastModified, metadata.fileSize, 1)
    }

    def toUndefinedMetadata(customMetadata: List[CustomMetadata]): List[Metadata] = fileRow.metadata.undefinedMetadata(customMetadata)
  }

  implicit class MetadataHelper(metadata: List[Metadata]) {
    private val clientSideMetadataProperties: Set[String] = Set("clientside_original_filepath", "file_size", "Checksum", "date_last_modified")

    def uuid: UUID = {
      UUID.fromString(metadata.find(_.name == "UUID").get.value)
    }

    def originalPath: String = {
      metadata.find(_.name == "clientside_original_filepath").get.value
    }

    def checksum: String = {
      metadata.find(_.name == "Checksum").get.value
    }

    def lastModified: Long = {
      metadata.find(_.name == "date_last_modified").get.value.toLong
    }

    def fileSize: Long = {
      metadata.find(_.name == "file_size").get.value.toLong
    }

    def undefinedMetadata(customMetadata: List[CustomMetadata]): List[Metadata] = {
      val definedMetadata = customMetadata.map(_.name).toSet
      metadata.filter(m => !definedMetadata.contains(m.name) && !clientSideMetadataProperties.contains(m.name))
    }
  }
}

object DataPersistenceHandler {
  case class MetadataInputs(clientsideMetadataInput: Option[ClientSideMetadataInput], additionalMetadataInput: AddOrUpdateFileMetadata, undefinedMetadata: List[Metadata] = List())

  def apply(draftMetadata: DraftMetadata, clientSecret: String, graphQlApi: GraphQlApi) = new DataPersistenceHandler(draftMetadata, clientSecret, graphQlApi)
}
