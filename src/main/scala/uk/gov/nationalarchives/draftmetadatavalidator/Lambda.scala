package uk.gov.nationalarchives.draftmetadatavalidator

import cats.effect.IO
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent
import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import graphql.codegen.AddOrUpdateBulkFileMetadata.{addOrUpdateBulkFileMetadata => afm}
import graphql.codegen.GetCustomMetadata.customMetadata.CustomMetadata
import graphql.codegen.GetCustomMetadata.{customMetadata => cm}
import graphql.codegen.GetDisplayProperties.displayProperties.DisplayProperties
import graphql.codegen.GetDisplayProperties.{displayProperties => dp}
import graphql.codegen.UpdateConsignmentStatus.{updateConsignmentStatus => ucs}
import graphql.codegen.types.DataType._
import graphql.codegen.types.{AddOrUpdateFileMetadata, AddOrUpdateMetadata}
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
import uk.gov.nationalarchives.tdr.GraphQLClient
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}
import uk.gov.nationalarchives.tdr.validation.schema.JsonSchemaDefinition.{BASE_SCHEMA, CLOSURE_SCHEMA}
import uk.gov.nationalarchives.tdr.validation.schema.{JsonSchemaDefinition, MetadataValidationJsonSchema, ValidationError, ValidationProcess}

import java.net.URI
import java.nio.file.{Files, Path, Paths}
import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util
import java.util.{Date, UUID}
import scala.concurrent.ExecutionContext.Implicits.global

class Lambda extends RequestHandler[java.util.Map[String, Object], APIGatewayProxyResponseEvent] {

  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()
  implicit val keycloakDeployment: TdrKeycloakDeployment = TdrKeycloakDeployment(authUrl, "tdr", timeToLiveSecs)
  implicit def logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  private val keycloakUtils = new KeycloakUtils()
  private val customMetadataClient = new GraphQLClient[cm.Data, cm.Variables](apiUrl)
  private val displayPropertiesClient = new GraphQLClient[dp.Data, dp.Variables](apiUrl)
  private val updateConsignmentStatusClient = new GraphQLClient[ucs.Data, ucs.Variables](apiUrl)
  private val addOrUpdateBulkFileMetadataClient = new GraphQLClient[afm.Data, afm.Variables](apiUrl)
  private val graphQlApi: GraphQlApi = GraphQlApi(keycloakUtils, customMetadataClient, updateConsignmentStatusClient, addOrUpdateBulkFileMetadataClient, displayPropertiesClient)

  def handleRequest(input: java.util.Map[String, Object], context: Context): APIGatewayProxyResponseEvent = {
    val consignmentId = extractConsignmentId(input)
    val schemaToValidate: Set[JsonSchemaDefinition] = Set(BASE_SCHEMA, CLOSURE_SCHEMA)
    val s3Files = S3Files(S3Utils(s3Async(s3Endpoint)))
    val unexpectedFailureResponse = new APIGatewayProxyResponseEvent()
    unexpectedFailureResponse.setStatusCode(500)

    val requestHandler: IO[APIGatewayProxyResponseEvent] = for {
      draftMetadata <- IO(DraftMetadata(UUID.fromString(consignmentId)))
      _ <- s3Files.downloadFile(bucket, draftMetadata)
      // check UTF 8
      utfCheckResult <- validUTF(draftMetadata)
      // check CSV is a CSV file -
      csvResultCheck <- if (noErrors(utfCheckResult)) validCSV(draftMetadata) else IO(utfCheckResult)
      // if valid csv load data
      data <- if (noErrors(csvResultCheck)) loadCSV(draftMetadata) else IO(List.empty[FileRow])
      // validate required fields (using separate check as only one row required and want to change key identifier to consignmentID)
      // treat required fields as consignment problem not specific row of data)
      requiredCheck <- validateRequired(data, consignmentId)
      // validate
      schemaCheck <- validateMetadata(data, schemaToValidate)
      // combine all errors (no need to use utfCheckResult
      validationResult <- combineErrors(Seq(schemaCheck, csvResultCheck, requiredCheck))
      // always write validation result file
      // maybe return emptyError list on success and handleError with an error
      writeDataResult <- IO(writeValidationResultToFile(draftMetadata, validationResult))
      // upload validation result file
      // maybe return emptyError list on success and handleError with an error
      uploadDataResult <- s3Files.uploadFile(bucket, s"${draftMetadata.consignmentId}/$errorFileName", getErrorFilePath(draftMetadata))
      // if no errors save metadata
      // maybe return emptyError list on success and handleError with an error
      persistMetaDataResult <- if (noErrors(validationResult)) persistMetadata(draftMetadata) else IO(Map.empty[String, Seq[ValidationError]])
      // update status  combine validationResult with writeDataResult, uploadDataResult, persistMetaDataResult and check all for errors
      _ <- updateStatus(validationResult, draftMetadata)
    } yield {
      val response = new APIGatewayProxyResponseEvent()
      response.setStatusCode(200)
      response
    }
    // let's stop blowing up on unexpected errors
    requestHandler.handleErrorWith(_ => IO(unexpectedFailureResponse)).unsafeRunSync()(cats.effect.unsafe.implicits.global)
  }

  // use a required schema, pass one row of data that will return missing required fields, change row identifier
  // to consignmentID. Can then use to help populate required on UI
  // just hack code
  private def validateRequired(csvData: List[FileRow], consignmentID: String) = {
    if (csvData.isEmpty) IO(Map.empty[String, Seq[ValidationError]])
    else // need required schema not BASE_SCHEMA
      for {
        errors <- validateMetadata(List(csvData.head), Set(BASE_SCHEMA))
        changedKeyToConsignmentId <- IO(errors.foldLeft(Map[String, Seq[ValidationError]]()) { case (acc, (_, v)) =>
          acc + (consignmentID -> List.empty[ValidationError])
        })
      } yield changedKeyToConsignmentId

  }

  // this might not be correct just added for initial testing
  private def combineErrors(errors: Seq[Map[String, Seq[ValidationError]]]) = {
    val combinedErrors = errors.flatten.foldLeft(Map[String, List[ValidationError]]()) { case (acc, (k, v)) =>
      acc + (k -> (acc.getOrElse(k, List.empty[ValidationError]) ++ v.toList))
    }
    IO(combinedErrors)
  }

  // Check file is UTF
  // maybe have new ValidationProcess.FILE_CHECK that can be used file integrity checks
  def validUTF(draftMetadata: DraftMetadata): IO[Map[String, Seq[ValidationError]]] = {
    // check UTF 8
    val p = IO(Map("consignmentId" -> Set(ValidationError(ValidationProcess.SCHEMA_BASE, "fileName", "badUTF8")).toSeq))
    // just returning no errors until checked
    IO(Map.empty[String, Seq[ValidationError]])
  }

  // Check file is a what is understood as a CSV header row and rows of data
  // maybe have new ValidationProcess.FILE_CHECK that can be used file integrity checks
  def validCSV(draftMetadata: DraftMetadata): IO[Map[String, Seq[ValidationError]]] = {
    // check  CSV
    // just returning no errors until checked
    IO(Map.empty[String, Seq[ValidationError]])
  }

  private def extractConsignmentId(input: util.Map[String, Object]): String = {
    val inputParameters = input match {
      case stepFunctionInput if stepFunctionInput.containsKey("consignmentId") => stepFunctionInput
      case apiProxyRequestInput if apiProxyRequestInput.containsKey("pathParameters") =>
        apiProxyRequestInput.get("pathParameters").asInstanceOf[util.Map[String, Object]]
    }
    inputParameters.get("consignmentId").toString
  }

  private def validateMetadata(csvData: List[FileRow], schema: Set[JsonSchemaDefinition]): IO[Map[String, Seq[ValidationError]]] = {
    val r = for {
      mapped <- IO(csvData.map(f => f))
      validationResult <- IO(MetadataValidationJsonSchema.validate(schema, mapped))
    } yield validationResult
    r
  }

  private def loadCSV(draftMetadata: DraftMetadata) = {
    val csvHandler = new CSVHandler()
    val filePath = getFilePath(draftMetadata)
    IO(csvHandler.loadCSV(filePath))
  }

  private def updateStatus(errorResults: Map[String, Seq[ValidationError]], draftMetadata: DraftMetadata) = {
    val clientSecret = getClientSecret(clientSecretPath, endpoint)
    val status = if (noErrors(errorResults)) "CompletedWithIssues" else "Completed"
    graphQlApi
      .updateConsignmentStatus(draftMetadata.consignmentId, clientSecret, "DraftMetadata", status)
  }

  private def persistMetadata(draftMetadata: DraftMetadata) = {
    val clientSecret = getClientSecret(clientSecretPath, endpoint)
    val csvHandler = new CSVHandler()
    for {
      customMetadata <- graphQlApi.getCustomMetadata(draftMetadata.consignmentId, clientSecret)
      displayProperties <- graphQlApi.getDisplayProperties(draftMetadata.consignmentId, clientSecret)
      fileData <- IO(csvHandler.loadCSV(getFilePath(draftMetadata), getMetadataNames(displayProperties, customMetadata)))
      _ <- graphQlApi.addOrUpdateBulkFileMetadata(draftMetadata.consignmentId, clientSecret, convertDataToBulkFileMetadataInput(fileData, customMetadata))

    } yield ()
  }

  private def noErrors(errors: Map[String, Seq[ValidationError]]) = {
    val errorsOnly = errors.filter(mapEntry => mapEntry._2.nonEmpty)
    errorsOnly.isEmpty
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

  private def convertDataToBulkFileMetadataInput(fileData: FileData, customMetadata: List[CustomMetadata]): List[AddOrUpdateFileMetadata] = {
    fileData.fileRows.collect {
      case fileRow if fileRow.metadata.exists(_.value.nonEmpty) =>
        AddOrUpdateFileMetadata(
          UUID.fromString(fileRow.matchIdentifier),
          fileRow.metadata.collect { case m if m.value.nonEmpty => createAddOrUpdateMetadata(m, customMetadata.find(_.name == m.name).get) }.flatten
        )
    }
  }

  private def createAddOrUpdateMetadata(metadata: Metadata, customMetadata: CustomMetadata): List[AddOrUpdateMetadata] = {
    val format = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val values = customMetadata.dataType match {
      case DateTime => Timestamp.valueOf(LocalDate.parse(metadata.value, format).atStartOfDay()).toString :: Nil
      case Boolean =>
        metadata.value.toLowerCase() match {
          case "yes" => "true" :: Nil
          case _     => "false" :: Nil
        }
      case Text if customMetadata.multiValue => metadata.value.split("\\|").toList
      case _                                 => metadata.value :: Nil
    }
    values.map(v => AddOrUpdateMetadata(metadata.name, v))
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

  private def getMetadataNames(displayProperties: List[DisplayProperties], customMetadata: List[CustomMetadata]): List[String] = {
    val nameMap = displayProperties.filter(dp => dp.attributes.find(_.attribute == "Active").getBoolean).map(_.propertyName)
    val filteredMetadata: List[CustomMetadata] = customMetadata.filter(cm => nameMap.contains(cm.name) && cm.allowExport).sortBy(_.exportOrdinal.getOrElse(Int.MaxValue))
    filteredMetadata.map(_.name)
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
