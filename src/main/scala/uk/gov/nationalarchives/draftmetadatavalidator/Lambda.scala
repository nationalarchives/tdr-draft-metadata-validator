package uk.gov.nationalarchives.draftmetadatavalidator

import cats.effect.IO
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent
import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import graphql.codegen.AddOrUpdateBulkFileMetadata.{addOrUpdateBulkFileMetadata => afm}
import graphql.codegen.GetCustomMetadata.{customMetadata => cm}
import graphql.codegen.GetDisplayProperties.displayProperties.DisplayProperties
import graphql.codegen.UpdateConsignmentStatus.{updateConsignmentStatus => ucs}
import io.circe.Encoder
import io.circe.generic.auto._
import io.circe.syntax._
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.GetParameterRequest
import sttp.client3.{HttpURLConnectionBackend, Identity, SttpBackend}
import uk.gov.nationalarchives.aws.utils.s3.S3Clients._
import uk.gov.nationalarchives.aws.utils.s3.S3Utils
import uk.gov.nationalarchives.draftmetadatavalidator.ApplicationConfig._
import uk.gov.nationalarchives.draftmetadatavalidator.Lambda.{DraftMetadata, getErrorFilePath, getFilePath}
import uk.gov.nationalarchives.draftmetadatavalidator.utils.MetadataUtils
import uk.gov.nationalarchives.tdr.GraphQLClient
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}
import uk.gov.nationalarchives.tdr.validation.schema.JsonSchemaDefinition.{BASE_SCHEMA, CLOSURE_SCHEMA}
import uk.gov.nationalarchives.tdr.validation.schema.{JsonSchemaDefinition, MetadataValidationJsonSchema, ValidationError, ValidationProcess}

import java.net.URI
import java.nio.file.{Files, Paths}
import java.text.SimpleDateFormat
import java.util
import java.util.{Date, UUID}
import scala.concurrent.ExecutionContext.Implicits.global

class Lambda extends RequestHandler[java.util.Map[String, Object], APIGatewayProxyResponseEvent] {

  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()
  implicit val keycloakDeployment: TdrKeycloakDeployment = TdrKeycloakDeployment(authUrl, "tdr", timeToLiveSecs)
  implicit def logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  private val keycloakUtils = new KeycloakUtils()
  private val customMetadataClient = new GraphQLClient[cm.Data, cm.Variables](apiUrl)
  private val updateConsignmentStatusClient = new GraphQLClient[ucs.Data, ucs.Variables](apiUrl)
  private val addOrUpdateBulkFileMetadataClient = new GraphQLClient[afm.Data, afm.Variables](apiUrl)
  private val graphQlApi: GraphQlApi = GraphQlApi(keycloakUtils, customMetadataClient, updateConsignmentStatusClient, addOrUpdateBulkFileMetadataClient)

  def handleRequest(input: java.util.Map[String, Object], context: Context): APIGatewayProxyResponseEvent = {
    val consignmentId = extractConsignmentId(input)
    val schemaToValidate: Set[JsonSchemaDefinition] = Set(BASE_SCHEMA, CLOSURE_SCHEMA)
    val s3Files = S3Files(S3Utils(s3Async(s3Endpoint)))
    for {
      draftMetadata <- IO(DraftMetadata(UUID.fromString(consignmentId)))
      _ <- s3Files.downloadFile(bucket, draftMetadata)
      validationResult <- validateMetadata(draftMetadata, schemaToValidate)
      _ <- IO(writeValidationResultToFile(draftMetadata, validationResult))
      _ <- s3Files.uploadFile(bucket, s"${draftMetadata.consignmentId}/$errorFileName", getErrorFilePath(draftMetadata))
      _ <- if (!hasErrors(validationResult)) persistMetadata(draftMetadata) else IO.unit
      _ <- updateStatus(validationResult, draftMetadata)
    } yield {
      val response = new APIGatewayProxyResponseEvent()
      response.setStatusCode(200)
      response
    }
  }.unsafeRunSync()(cats.effect.unsafe.implicits.global)

  private def extractConsignmentId(input: util.Map[String, Object]): String = {
    val inputParameters = input match {
      case stepFunctionInput if stepFunctionInput.containsKey("consignmentId") => stepFunctionInput
      case apiProxyRequestInput if apiProxyRequestInput.containsKey("pathParameters") =>
        apiProxyRequestInput.get("pathParameters").asInstanceOf[util.Map[String, Object]]
    }
    inputParameters.get("consignmentId").toString
  }

  private def validateMetadata(draftMetadata: DraftMetadata, schema: Set[JsonSchemaDefinition]): IO[Map[String, Seq[ValidationError]]] = {
    val csvHandler = new CSVHandler()
    val filePath = getFilePath(draftMetadata)
    for {
      fileRows <- IO(csvHandler.loadCSV(filePath))
      validationResult <- IO(MetadataValidationJsonSchema.validate(schema, fileRows))
    } yield validationResult
  }

  private def updateStatus(errorResults: Map[String, Seq[ValidationError]], draftMetadata: DraftMetadata) = {
    val clientSecret = getClientSecret(clientSecretPath, endpoint)
    val status = if (hasErrors(errorResults)) "CompletedWithIssues" else "Completed"
    graphQlApi
      .updateConsignmentStatus(draftMetadata.consignmentId, clientSecret, "DraftMetadata", status)
  }

  private def persistMetadata(draftMetadata: DraftMetadata) = {
    val clientSecret = getClientSecret(clientSecretPath, endpoint)
    val csvHandler = new CSVHandler()
    for {
      customMetadata <- graphQlApi.getCustomMetadata(draftMetadata.consignmentId, clientSecret)
      fileData <- IO(csvHandler.loadCSV(getFilePath(draftMetadata), getMetadataNames()))
      addOrUpdateBulkFileMetadata = MetadataUtils.filterProtectedFields(customMetadata, fileData)
      _ <- graphQlApi.addOrUpdateBulkFileMetadata(draftMetadata.consignmentId, clientSecret, addOrUpdateBulkFileMetadata)

    } yield ()
  }

  private def hasErrors(errors: Map[String, Seq[ValidationError]]) = {
    val errorsOnly = errors.filter(mapEntry => mapEntry._2.nonEmpty)
    errorsOnly.nonEmpty
  }

  private def writeValidationResultToFile(draftMetadata: DraftMetadata, errors: Map[String, Seq[ValidationError]]): Unit = {
    case class ValidationErrors(assetId: String, errors: Set[ValidationError])
    case class ErrorFileData(consignmentId: UUID, date: String, validationErrors: List[ValidationErrors])

    val filteredErrors = errors.filter(mapEntry => mapEntry._2.nonEmpty)
    val validationErrors = filteredErrors.keys.map(key => ValidationErrors(key, filteredErrors(key).toSet)).toList

    // Ignore Intellij this is used by circe
    implicit val validationProcessEncoder: Encoder[ValidationProcess.Value] = Encoder.encodeEnumeration(ValidationProcess)
    val pattern = "yyyy-MM-dd"
    val dateFormat = new SimpleDateFormat(pattern)
    val json = ErrorFileData(draftMetadata.consignmentId, dateFormat.format(new Date), validationErrors).asJson.toString()
    Files.writeString(Paths.get(getErrorFilePath(draftMetadata)), json)
  }

  private def getClientSecret(secretPath: String, endpoint: String): String = {
    val httpClient = ApacheHttpClient.builder.build
    val ssmClient: SsmClient = SsmClient
      .builder()
      .endpointOverride(URI.create(endpoint))
      .httpClient(httpClient)
      .region(Region.EU_WEST_2)
      .build()
    val getParameterRequest = GetParameterRequest.builder.name(secretPath).withDecryption(true).build
    ssmClient.getParameter(getParameterRequest).parameter().value()
  }

  private def getMetadataNames(): List[String] = {
    // This is a temporary change to fix the issue related to order of the columns. We should use the schema to get the DB property name
    val columnOrder = List(
      "ClientSideOriginalFilepath",
      "Filename",
      "ClientSideFileLastModifiedDate",
      "end_date",
      "description",
      "former_reference_department",
      "ClosureType",
      "ClosureStartDate",
      "ClosurePeriod",
      "FoiExemptionCode",
      "FoiExemptionAsserted",
      "TitleClosed",
      "TitleAlternate",
      "DescriptionClosed",
      "DescriptionAlternate",
      "Language",
      "file_name_translation",
      "UUID"
    )
    columnOrder
  }

  implicit class AttributeHelper(attribute: Option[DisplayProperties.Attributes]) {

    def getBoolean: Boolean = {
      attribute match {
        case Some(a) => a.value.contains("true")
        case _       => false
      }
    }
  }
}

object Lambda {
  case class DraftMetadata(consignmentId: UUID)
  def getFilePath(draftMetadata: DraftMetadata) = s"""$rootDirectory/${draftMetadata.consignmentId}/$fileName"""
  def getErrorFilePath(draftMetadata: DraftMetadata) = s"""$rootDirectory/${draftMetadata.consignmentId}/$errorFileName"""
  def getFolderPath(draftMetadata: DraftMetadata) = s"""$rootDirectory/${draftMetadata.consignmentId}"""
}
