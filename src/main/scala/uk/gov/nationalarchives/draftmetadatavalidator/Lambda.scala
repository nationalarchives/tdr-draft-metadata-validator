package uk.gov.nationalarchives.draftmetadatavalidator

import cats.effect.IO
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent
import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import graphql.codegen.AddOrUpdateBulkFileMetadata.addOrUpdateBulkFileMetadata.AddOrUpdateBulkFileMetadata
import graphql.codegen.AddOrUpdateBulkFileMetadata.{addOrUpdateBulkFileMetadata => afm}
import graphql.codegen.GetCustomMetadata.{customMetadata => cm}
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
import uk.gov.nationalarchives.draftmetadatavalidator.Lambda.{ValidationExecutionError, ValidationParameters, getErrorFilePath, getFilePath}
import uk.gov.nationalarchives.draftmetadatavalidator.utils.MetadataUtils
import uk.gov.nationalarchives.tdr.GraphQLClient
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}
import uk.gov.nationalarchives.tdr.schemautils.SchemaUtils.convertToAlternateKey
import uk.gov.nationalarchives.tdr.validation.FileRow
import uk.gov.nationalarchives.tdr.validation.schema.JsonSchemaDefinition.{BASE_SCHEMA, CLOSURE_SCHEMA_CLOSED, CLOSURE_SCHEMA_OPEN}
import uk.gov.nationalarchives.tdr.validation.schema.{JsonSchemaDefinition, MetadataValidationJsonSchema}

import java.io.FileInputStream
import java.net.URI
import java.nio.file.{Files, Paths}
import java.util
import java.util.{Properties, UUID}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.io.Source

class Lambda extends RequestHandler[java.util.Map[String, Object], APIGatewayProxyResponseEvent] {

  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()
  implicit val keycloakDeployment: TdrKeycloakDeployment = TdrKeycloakDeployment(authUrl, "tdr", timeToLiveSecs)
  implicit def logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]
  implicit val fileErrorEncoder: Encoder[FileError.Value] = Encoder.encodeEnumeration(FileError)
  private lazy val messageProperties = getMessageProperties

  private val keycloakUtils = new KeycloakUtils()
  private val customMetadataClient = new GraphQLClient[cm.Data, cm.Variables](apiUrl)
  private val updateConsignmentStatusClient = new GraphQLClient[ucs.Data, ucs.Variables](apiUrl)
  private val addOrUpdateBulkFileMetadataClient = new GraphQLClient[afm.Data, afm.Variables](apiUrl)
  private val graphQlApi: GraphQlApi = GraphQlApi(keycloakUtils, customMetadataClient, updateConsignmentStatusClient, addOrUpdateBulkFileMetadataClient)

  def handleRequest(input: java.util.Map[String, Object], context: Context): APIGatewayProxyResponseEvent = {
    val consignmentId = extractConsignmentId(input)
    val schemaToValidate: Set[JsonSchemaDefinition] = Set(BASE_SCHEMA, CLOSURE_SCHEMA_CLOSED, CLOSURE_SCHEMA_OPEN)
    val validationParameters: ValidationParameters = ValidationParameters(UUID.fromString(consignmentId), schemaToValidate, "UUID", "tdrFileHeader")

    val requestHandler: IO[APIGatewayProxyResponseEvent] = for {
      errorFileData <- doValidation(validationParameters)
      _ <- writeErrorFileDataToFile(validationParameters, errorFileData)
      _ <- if (errorFileData.validationErrors.isEmpty) persistMetadata(validationParameters) else IO.unit
      _ <- updateStatus(errorFileData, validationParameters)
    } yield {
      val response = new APIGatewayProxyResponseEvent()
      response.setStatusCode(200)
      response
    }

    requestHandler
      .handleErrorWith(error => {
        logger.error(s"Unexpected validation problem:${error.getMessage}")
        val unexpectedFailureResponse = new APIGatewayProxyResponseEvent()
        unexpectedFailureResponse.setStatusCode(500)
        unexpectedFailureResponse.withBody(s"Unexpected validation problem:${error.getMessage}")
        IO(unexpectedFailureResponse)
      })
      .unsafeRunSync()(cats.effect.unsafe.implicits.global)
  }

  private def doValidation(validationParameters: ValidationParameters): IO[ErrorFileData] = {
    val s3Files = S3Files(S3Utils(s3Async(s3Endpoint)))
    val validationProgram = for {
      _ <- s3Files.downloadFile(bucket, validationParameters)
      _ <- validUTF8(validationParameters)
      csvData <- loadCSV(validationParameters)
      _ <- validateRequired(csvData, validationParameters)
      _ <- validateMetadata(validationParameters, csvData)
    } yield ErrorFileData(validationParameters, FileError.None, List.empty[ValidationErrors])

    // all validations will raise ValidationExecutionError if they do not pass
    validationProgram.handleError({
      case validationExecutionError: ValidationExecutionError => validationExecutionError.errorFileData
      case err =>
        logger.error(s"Error doing validation for consignment:${validationParameters.consignmentId.toString} :${err.getMessage}")
        val singleError = Error("Validation", validationParameters.consignmentId.toString, "UNKNOWN", err.getMessage)
        val validationErrors = ValidationErrors(validationParameters.consignmentId.toString, Set(singleError))
        ErrorFileData(validationParameters, FileError.UNKNOWN, List(validationErrors))
    })
  }

  // use a required schema, pass one row of data that will return missing required fields, change row identifier
  // to consignmentID. Can then use to help populate required on UI
  // just hack code
  private def validateRequired(csvData: List[FileRow], validationParameters: ValidationParameters): IO[Unit] = {
    // TODO: To be implemented TDRD-62
    IO.unit
  }

  private def validUTF8(validationParameters: ValidationParameters): IO[Unit] = {
    val filePath = getFilePath(validationParameters)

    def utf8ErrorData(messageVal: String = "Not valid UTF-8 no BOM") = {
      val messageKey = "FILE_CHECK.UTF.INVALID"
      val message = if (messageProperties.containsKey(messageKey)) messageProperties.getProperty(messageKey) else messageVal
      val singleError = Error("FILE_CHECK", validationParameters.consignmentId.toString, "UTF8", message)
      val validationErrors = ValidationErrors(validationParameters.consignmentId.toString, Set(singleError))
      ErrorFileData(validationParameters, FileError.UTF_8, List(validationErrors))
    }

    def checkBOM(inputStream: FileInputStream) = {
      val bytesArray = new Array[Byte](3)
      inputStream.read(bytesArray)
      inputStream.close()
      val bom = "EFBBBF".sliding(2, 2).map(Integer.parseInt(_, 16).toByte).toArray
      if (bom sameElements bytesArray)
        IO.unit
      else {
        IO.raiseError(ValidationExecutionError(utf8ErrorData("Invalid CSV No valid BOM"), List.empty[FileRow]))
      }
    }

    for {
      inputStream <- IO(new FileInputStream(filePath))
      _ <- checkBOM(inputStream)
    } yield ()

  }

  private def extractConsignmentId(input: util.Map[String, Object]): String = {
    val inputParameters = input match {
      case stepFunctionInput if stepFunctionInput.containsKey("consignmentId") => stepFunctionInput
      case apiProxyRequestInput if apiProxyRequestInput.containsKey("pathParameters") =>
        apiProxyRequestInput.get("pathParameters").asInstanceOf[util.Map[String, Object]]
    }
    inputParameters.get("consignmentId").toString
  }

  private def validateMetadata(validationParameters: ValidationParameters, csvData: List[FileRow]): IO[ErrorFileData] = {
    val validationErrors = MetadataValidationJsonSchema
      .validate(validationParameters.schemaToValidate, csvData)
      .collect {
        case result if result._2.nonEmpty =>
          val errors = result._2.map(error => {
            val errorKey = s"${error.validationProcess}.${error.property}.${error.errorKey}"
            Error(
              error.validationProcess.toString,
              convertToAlternateKey(validationParameters.alternateKey, error.property) match {
                case ""           => error.property
                case alternateKey => alternateKey
              },
              error.errorKey,
              messageProperties.getProperty(errorKey, errorKey)
            )
          })
          val errorProperties = errors.map(_.property) :+ "Filepath"
          val data = csvData.find(_.matchIdentifier == result._1).get.metadata.filter(p => errorProperties.contains(p.name))
          ValidationErrors(result._1, errors.toSet, data)
      }
      .toList

    if (validationErrors.nonEmpty) {
      IO.raiseError(ValidationExecutionError(ErrorFileData(validationParameters, FileError.SCHEMA_VALIDATION, validationErrors), csvData))
    } else {
      IO(ErrorFileData(validationParameters))
    }
  }

  private def getMessageProperties: Properties = {
    val source = Source.fromURL(getClass.getResource("/validation-messages/validation-messages.properties"))
    val properties = new Properties()
    properties.load(source.bufferedReader())
    properties
  }

  private def loadCSV(validationParameters: ValidationParameters): IO[List[FileRow]] = {

    def csvErrorData(messageVal: String) = {
      val messageKey = "FILE_CHECK.CSV.INVALID"
      val message = if (messageProperties.containsKey(messageKey)) messageProperties.getProperty(messageKey) else messageVal
      val singleError = Error("FILE_CHECK", validationParameters.consignmentId.toString, "CSV", message)
      val validationErrors = ValidationErrors(validationParameters.consignmentId.toString, Set(singleError))
      ErrorFileData(validationParameters, FileError.INVALID_CSV, List(validationErrors))
    }

    val csvHandler = new CSVHandler()
    val filePath = getFilePath(validationParameters)
    IO(csvHandler.loadCSV(filePath, validationParameters.uniqueAssetIDKey)).handleErrorWith(err => {
      logger.error(s"Metadata Validation failed to load csv :${err.getMessage}")
      IO.raiseError(ValidationExecutionError(csvErrorData("Not valid CSV failed to load"), List.empty[FileRow]))
    })
  }

  private def updateStatus(errorFileData: ErrorFileData, draftMetadata: ValidationParameters): IO[Option[Int]] = {
    val clientSecret = getClientSecret(clientSecretPath, endpoint)
    val status = if (errorFileData.validationErrors.isEmpty) "Completed" else "CompletedWithIssues"
    graphQlApi.updateConsignmentStatus(draftMetadata.consignmentId, clientSecret, "DraftMetadata", status)
  }

  private def persistMetadata(draftMetadata: ValidationParameters): IO[List[AddOrUpdateBulkFileMetadata]] = {
    val clientSecret = getClientSecret(clientSecretPath, endpoint)
    val csvHandler = new CSVHandler()
    for {
      customMetadata <- graphQlApi.getCustomMetadata(draftMetadata.consignmentId, clientSecret)
      fileData <- IO(csvHandler.loadCSV(getFilePath(draftMetadata), getMetadataNames()))
      addOrUpdateBulkFileMetadata = MetadataUtils.filterProtectedFields(customMetadata, fileData)
      result <- graphQlApi.addOrUpdateBulkFileMetadata(draftMetadata.consignmentId, clientSecret, addOrUpdateBulkFileMetadata)
    } yield result
  }

  private def writeErrorFileDataToFile(validationParameters: ValidationParameters, errorFileData: ErrorFileData) = {
    val s3Files = S3Files(S3Utils(s3Async(s3Endpoint)))
    val json = errorFileData.asJson.toString()
    val errorFilePath = getErrorFilePath(validationParameters)
    for {
      _ <- IO(Files.writeString(Paths.get(errorFilePath), json))
      _ <- s3Files.uploadFile(bucket, s"${validationParameters.consignmentId}/$errorFileName", errorFilePath)
    } yield ()

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
}

object Lambda {

  case class ValidationExecutionError(errorFileData: ErrorFileData, csvData: List[FileRow]) extends Throwable
  case class ValidationParameters(consignmentId: UUID, schemaToValidate: Set[JsonSchemaDefinition], uniqueAssetIDKey: String, alternateKey: String)
  def getFilePath(draftMetadata: ValidationParameters) = s"""$rootDirectory/${draftMetadata.consignmentId}/$fileName"""
  def getErrorFilePath(draftMetadata: ValidationParameters) = s"""$rootDirectory/${draftMetadata.consignmentId}/$errorFileName"""
  def getFolderPath(draftMetadata: ValidationParameters) = s"""$rootDirectory/${draftMetadata.consignmentId}"""
}
