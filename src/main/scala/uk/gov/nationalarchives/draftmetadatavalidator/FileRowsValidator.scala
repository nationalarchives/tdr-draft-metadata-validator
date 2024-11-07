package uk.gov.nationalarchives.draftmetadatavalidator

import cats.effect.IO
import uk.gov.nationalarchives.draftmetadatavalidator.GraphQlApi.PersistedData

import java.util.{Properties, UUID}

object FileRowsValidator {

  private val invalidRowErrorKey = FileError.ROW_VALIDATION.toString
  private val unknownErrorKey = s"$invalidRowErrorKey.unknown"
  private val duplicateErrorKey = s"$invalidRowErrorKey.duplicate"
  private val missingErrorKey = s"$invalidRowErrorKey.missing"

  def validateMissingRows(
      persistedRowKeyToFiledId: Map[String, UUID],
      sourceUniqueRowKeys: List[String],
      messageProperties: Properties
  ): Seq[(String, Error)] = {

    def missingRow(persistedUniqueRowKey: String): Boolean = {
      !sourceUniqueRowKeys.contains(persistedUniqueRowKey)
    }

    persistedRowKeyToFiledId.keys
      .collect {
        case s if missingRow(s) => Map(s -> Error(invalidRowErrorKey, "", "missing", messageProperties.getProperty(missingErrorKey, missingErrorKey)))
      }
      .flatten
      .toSeq
  }

  def validateUnknownRows(
      persistedRowKeyToFiledId: Map[String, UUID],
      sourceUniqueRowKeys: List[String],
      messageProperties: Properties
  ): Seq[(String, Error)] = {
    val persistedUniqueRowKey = persistedRowKeyToFiledId.keys.toSeq
    def unknownRow(sourceUniqueRowKey: String): Boolean = {
      !persistedUniqueRowKey.contains(sourceUniqueRowKey)
    }

    sourceUniqueRowKeys
      .groupBy(identity)
      .collect {
        case (identifier, _) if unknownRow(identifier) =>
          Map(identifier -> Error(invalidRowErrorKey, "", "unknown", messageProperties.getProperty(unknownErrorKey, unknownErrorKey)))
      }
      .flatten
      .toSeq
  }

  def validateDuplicateRows(sourceUniqueRowKeys: List[String], messageProperties: Properties): Seq[(String, Error)] = {
    def duplicateRow(values: List[Any]): Boolean = {
      values.size > 1
    }

    sourceUniqueRowKeys
      .groupBy(identity)
      .collect {
        case (identifier, values) if duplicateRow(values) =>
          Map(identifier -> Error(invalidRowErrorKey, "", "duplicate", messageProperties.getProperty(duplicateErrorKey, duplicateErrorKey)))
      }
      .flatten
      .toSeq
  }
}
