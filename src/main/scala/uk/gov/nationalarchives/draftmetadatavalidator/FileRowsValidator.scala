package uk.gov.nationalarchives.draftmetadatavalidator

import java.util.UUID

object FileRowsValidator {

  //Temp case class until retrieve data from DB
  case class PersistedData(fileId: UUID, filePath: String)

  //Temp data until retrieve data from DB
  private val mockedPersistedData = Set(
    PersistedData(UUID.fromString("cbf2cba5-f1dc-45bd-ae6d-2b042336ce6c"), "test/test1.txt"),
    PersistedData(UUID.fromString("c4d5e0f1-f6e1-4a77-a7c0-a4317404da00"), "test/test2.txt"),
    PersistedData(UUID.fromString("a060c57d-1639-4828-9a7a-67a7c64dbf6c"), "test/test3.txt")
  )

  def validateRows(data: Set[PersistedData] = mockedPersistedData, csvData: List[String]): List[ValidationErrors] = {
    val persistedIdentifiers = data.map(_.fileId.toString)

    def missingRow(identifier: String): Boolean = {
      !csvData.contains(identifier)
    }

    def unknownRow(identifier: String): Boolean = {
      !persistedIdentifiers.contains(identifier)
    }

    def duplicateRow(values: List[Any]): Boolean = {
      values.size > 1
    }

    val invalidRowErrorKey = FileError.INVALID_ROW.toString
    val errors = csvData
      .groupBy(identity)
      .collect {
        case (identifier, _) if unknownRow(identifier) =>
          Map(identifier -> Error(invalidRowErrorKey, "", "unknown", s"$invalidRowErrorKey.unknown"))
        case (identifier, values) if duplicateRow(values) =>
          Map(identifier -> Error(invalidRowErrorKey, "", "duplicate", s"${invalidRowErrorKey}.duplicate"))
        case _ => Map()
      }
      .flatten
      .toMap

    val missingErrors = persistedIdentifiers
      .collect {
        case s if missingRow(s) => Map(s -> Error(invalidRowErrorKey, "", "missing", s"${invalidRowErrorKey}.missing"))
      }
      .flatten
      .toMap

    (missingErrors ++ errors).map(err => ValidationErrors(err._1, Set(err._2))).toList
  }
}
