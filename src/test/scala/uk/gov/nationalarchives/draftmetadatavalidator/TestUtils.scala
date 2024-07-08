package uk.gov.nationalarchives.draftmetadatavalidator

import graphql.codegen.GetCustomMetadata.customMetadata.CustomMetadata
import graphql.codegen.types.DataType
import graphql.codegen.types.DataType.Text
import graphql.codegen.types.PropertyType.Defined

object TestUtils {

  def createCustomMetadata(name: String, fullName: String, exportOrdinal: Int, dataType: DataType = Text, allowExport: Boolean = true, editable: Boolean = true): CustomMetadata = CustomMetadata(
    name,
    None,
    Some(fullName),
    Defined,
    Some("MandatoryClosure"),
    dataType,
    editable,
    multiValue = false,
    Some("Open"),
    1,
    Nil,
    Option(exportOrdinal),
    allowExport = allowExport
  )
}
