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
      persistedData: Map[String, UUID],
      csvMatchIdentifiers: List[String],
      messageProperties: Properties
  ): Seq[(String, Error)] = {

    def missingRow(matchIdentifier: String): Boolean = {
      !csvMatchIdentifiers.contains(matchIdentifier)
    }

    persistedData.keys
      .collect {
        case s if missingRow(s) => Map(s -> Error(invalidRowErrorKey, "", "missing", messageProperties.getProperty(missingErrorKey, missingErrorKey)))
      }
      .flatten
      .toSeq
  }

  def validateUnknownRows(
      persistedData: Map[String, UUID],
      csvMatchIdentifiers: List[String],
      messageProperties: Properties
  ): Seq[(String, Error)] = {
    val persistedMatchIdentifiers = persistedData.keys.toSeq
    def unknownRow(matchIdentifier: String): Boolean = {
      !persistedMatchIdentifiers.contains(matchIdentifier)
    }

    csvMatchIdentifiers
      .groupBy(identity)
      .collect {
        case (identifier, _) if unknownRow(identifier) =>
          Map(identifier -> Error(invalidRowErrorKey, "", "unknown", messageProperties.getProperty(unknownErrorKey, unknownErrorKey)))
      }
      .flatten
      .toSeq
  }

  def validateDuplicateRows(matchIdentifiers: List[String], messageProperties: Properties): Seq[(String, Error)] = {
    def duplicateRow(values: List[Any]): Boolean = {
      values.size > 1
    }

    matchIdentifiers
      .groupBy(identity)
      .collect {
        case (identifier, values) if duplicateRow(values) =>
          Map(identifier -> Error(invalidRowErrorKey, "", "duplicate", messageProperties.getProperty(duplicateErrorKey, duplicateErrorKey)))
      }
      .flatten
      .toSeq
  }
}
