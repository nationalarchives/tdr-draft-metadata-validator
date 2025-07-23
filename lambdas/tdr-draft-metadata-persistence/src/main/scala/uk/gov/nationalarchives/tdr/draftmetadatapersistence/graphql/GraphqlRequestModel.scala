package uk.gov.nationalarchives.tdr.draftmetadatapersistence.grapgql

import graphql.codegen.AddOrUpdateBulkFileMetadata.{addOrUpdateBulkFileMetadata => afm}
import graphql.codegen.UpdateConsignmentMetadataSchemaLibraryVersion.{updateConsignmentMetadataSchemaLibraryVersion => ucslv}
import graphql.codegen.types._
import io.circe._
import io.circe.generic.semiauto._

object GraphqlRequestModel {
  implicit val addOrUpdateFileMetadataDecoder: Decoder[AddOrUpdateFileMetadata] = deriveDecoder
  implicit val addOrUpdateMetadataDecoder: Decoder[AddOrUpdateMetadata] = deriveDecoder
  implicit val addOrUpdateBulkFileMetadataInputDecoder: Decoder[AddOrUpdateBulkFileMetadataInput] = deriveDecoder
  implicit val afmVariablesDecoder: Decoder[afm.Variables] = deriveDecoder
  implicit val ucslvVariablesDecoder: Decoder[ucslv.Variables] = deriveDecoder
}
