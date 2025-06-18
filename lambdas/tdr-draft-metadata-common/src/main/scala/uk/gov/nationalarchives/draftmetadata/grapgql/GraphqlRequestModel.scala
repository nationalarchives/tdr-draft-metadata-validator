package uk.gov.nationalarchives.draftmetadata.grapgql

import graphql.codegen.AddOrUpdateBulkFileMetadata.{addOrUpdateBulkFileMetadata => afm}
import graphql.codegen.UpdateConsignmentMetadataSchemaLibraryVersion.{updateConsignmentMetadataSchemaLibraryVersion => ucslv}
import graphql.codegen.types._
import io.circe._
import io.circe.generic.semiauto._

// Case classes for GraphQL request payloads
case class AddOrUpdateBulkFileMetadataGraphqlRequestData(query: String, variables: afm.Variables)
case class UpdateConsignmentMetadataSchemaLibraryVersionGraphqlRequestData(query: String, variables: ucslv.Variables)

// Companion object with implicit decoders
object GraphqlRequestModel {
  // Needed for nested decoders
  implicit val addOrUpdateFileMetadataDecoder: Decoder[AddOrUpdateFileMetadata] = deriveDecoder
  implicit val addOrUpdateMetadataDecoder: Decoder[AddOrUpdateMetadata] = deriveDecoder

  implicit val addOrUpdateBulkFileMetadataInputDecoder: Decoder[AddOrUpdateBulkFileMetadataInput] = deriveDecoder
  implicit val updateMetadataSchemaLibraryVersionInputDecoder: Decoder[UpdateMetadataSchemaLibraryVersionInput] = deriveDecoder

  // Decoders for the afm.Variables and ucslv.Variables
  implicit val afmVariablesDecoder: Decoder[afm.Variables] = deriveDecoder
  implicit val ucslvVariablesDecoder: Decoder[ucslv.Variables] = deriveDecoder

  // Top-level decoders for the request data classes
  implicit val addOrUpdateBulkFileMetadataGraphqlRequestDataDecoder: Decoder[AddOrUpdateBulkFileMetadataGraphqlRequestData] = deriveDecoder
  implicit val updateConsignmentMetadataSchemaLibraryVersionGraphqlRequestDataDecoder: Decoder[UpdateConsignmentMetadataSchemaLibraryVersionGraphqlRequestData] = deriveDecoder
}
