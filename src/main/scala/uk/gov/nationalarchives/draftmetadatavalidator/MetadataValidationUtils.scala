package uk.gov.nationalarchives.draftmetadatavalidator

import graphql.codegen.GetCustomMetadata.customMetadata.CustomMetadata
import graphql.codegen.types.DataType
import uk.gov.nationalarchives.tdr.validation
import uk.gov.nationalarchives.tdr.validation.{MetadataCriteria, MetadataValidation}

object MetadataValidationUtils {

  def createMetadataValidation(fields: List[CustomMetadata]): MetadataValidation = {

    val closureFields = fields.filter(f => f.name == "ClosureType")
    val descriptiveFields = fields.filter(f => f.propertyGroup.contains("OptionalMetadata"))

    val closureMetadataCriteria = createCriteria(closureFields, fields).head
    val descriptiveMetadataCriteria = createCriteria(descriptiveFields, fields)
    new MetadataValidation(closureMetadataCriteria, descriptiveMetadataCriteria)
  }

  private def createCriteria(customMetadata: List[CustomMetadata], allCustomMetadata: List[CustomMetadata]): List[MetadataCriteria] = {
    customMetadata.map(cm => {
      val (definedValues, defaultValue) = cm.dataType match {
        case DataType.Boolean =>
          (
            List("Yes", "No"),
            Some(cm.defaultValue match {
              case Some("true") => "Yes"
              case _            => "No"
            })
          )
        case DataType.Text => (cm.values.map(_.value), cm.defaultValue)
        case _             => (List(), cm.defaultValue)
      }
      val requiredField: Boolean = cm.propertyGroup.contains("MandatoryClosure") || cm.propertyGroup.contains("MandatoryMetadata")
      MetadataCriteria(
        cm.name,
        validation.DataType.get(cm.dataType.toString),
        requiredField,
        isFutureDateAllowed = false,
        isMultiValueAllowed = cm.multiValue,
        definedValues,
        defaultValue = defaultValue,
        dependencies = Some(cm.values.map(v => v.value -> createCriteria(allCustomMetadata.filter(m => v.dependencies.exists(_.name == m.name)), allCustomMetadata)).toMap)
      )
    })
  }
}
