package uk.gov.nationalarchives

package object draftmetadata {
  /**
   * Represents a row of file data with its associated metadata.
   *
   * @param matchIdentifier A unique identifier for the file, used for matching with file details.
   * @param metadata The list of metadata items associated with this file.
   */
  case class FileRow(matchIdentifier: String, metadata: List[Metadata])

  /**
   * Represents a single metadata field for a file.
   *
   * @param name The name/key of the metadata field.
   * @param value The value of the metadata field.
   */
  case class Metadata(name: String, value: String)

}
