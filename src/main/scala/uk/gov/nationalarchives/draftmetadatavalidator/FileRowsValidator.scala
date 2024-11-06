package uk.gov.nationalarchives.draftmetadatavalidator

import java.util.{Properties, UUID}

object FileRowsValidator {

  // Temp case class until retrieve data from DB
  case class PersistedData(fileId: UUID, filePath: String)

  // Temp data until retrieve data from DB
  private val mockedPersistedData = Set(
    PersistedData(UUID.fromString("cbf2cba5-f1dc-45bd-ae6d-2b042336ce6c"), "test/test1.txt"),
    PersistedData(UUID.fromString("c4d5e0f1-f6e1-4a77-a7c0-a4317404da00"), "test/test2.txt"),
    PersistedData(UUID.fromString("a060c57d-1639-4828-9a7a-67a7c64dbf6c"), "test/test3.txt")
  )

  private val invalidRowErrorKey = FileError.ROW_VALIDATION.toString
  private val unknownErrorKey = s"$invalidRowErrorKey.unknown"
  private val duplicateErrorKey = s"$invalidRowErrorKey.duplicate"
  private val missingErrorKey = s"$invalidRowErrorKey.missing"

  def validateMissingRows(
      persistedMatchIdentifiers: Set[String] = mockedPersistedData.map(_.fileId.toString),
      csvMatchIdentifiers: List[String],
      messageProperties: Properties
  ): Seq[(String, Error)] = {
    def missingRow(matchIdentifier: String): Boolean = {
      !csvMatchIdentifiers.contains(matchIdentifier)
    }

    persistedMatchIdentifiers
      .collect {
        case s if missingRow(s) => Map(s -> Error(invalidRowErrorKey, "", "missing", messageProperties.getProperty(missingErrorKey, missingErrorKey)))
      }
      .flatten
      .toSeq
  }

  def validateUnknownRows(
      persistedMatchIdentifiers: Set[String] = mockedPersistedData.map(_.fileId.toString),
      csvMatchIdentifiers: List[String],
      messageProperties: Properties
  ): Seq[(String, Error)] = {
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

  def validateDuplicateRows(matchIdentifiers: List[String], messageProperties: Properties): List[ValidationErrors] = {
    def duplicateRow(values: List[Any]): Boolean = {
      values.size > 1
    }

    val duplicateRowErrors = matchIdentifiers
      .groupBy(identity)
      .collect {
        case (identifier, values) if duplicateRow(values) =>
          Map(identifier -> Error(invalidRowErrorKey, "", "duplicate", messageProperties.getProperty(duplicateErrorKey, duplicateErrorKey)))
      }
      .flatten
      .toMap

    duplicateRowErrors.map(err => ValidationErrors(err._1, Set(err._2))).toList
  }
}
