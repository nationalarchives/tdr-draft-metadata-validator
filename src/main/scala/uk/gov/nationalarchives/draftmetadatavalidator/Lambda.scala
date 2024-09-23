package uk.gov.nationalarchives.draftmetadatavalidator

import cats.effect.IO
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent
import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
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
import uk.gov.nationalarchives.draftmetadatavalidator.Lambda.{DraftMetadata, getErrorFilePath, getFilePath}
import uk.gov.nationalarchives.draftmetadatavalidator.utils.MetadataUtils
import uk.gov.nationalarchives.tdr.GraphQLClient
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}
import uk.gov.nationalarchives.tdr.schemautils.SchemaUtils.convertToAlternateKey
import uk.gov.nationalarchives.tdr.validation.FileRow
import uk.gov.nationalarchives.tdr.validation.schema.JsonSchemaDefinition.{BASE_SCHEMA, CLOSURE_SCHEMA_CLOSED, CLOSURE_SCHEMA_OPEN}
import uk.gov.nationalarchives.tdr.validation.schema.{JsonSchemaDefinition, MetadataValidationJsonSchema}

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

  private val keycloakUtils = new KeycloakUtils()
  private val customMetadataClient = new GraphQLClient[cm.Data, cm.Variables](apiUrl)
  private val updateConsignmentStatusClient = new GraphQLClient[ucs.Data, ucs.Variables](apiUrl)
  private val addOrUpdateBulkFileMetadataClient = new GraphQLClient[afm.Data, afm.Variables](apiUrl)
  private val graphQlApi: GraphQlApi = GraphQlApi(keycloakUtils, customMetadataClient, updateConsignmentStatusClient, addOrUpdateBulkFileMetadataClient)
  private val defaultAlternateKeyType = "tdrFileHeader"

  def handleRequest(input: java.util.Map[String, Object], context: Context): APIGatewayProxyResponseEvent = {
    val consignmentId = extractConsignmentId(input)
    val schemaToValidate: Set[JsonSchemaDefinition] = Set(BASE_SCHEMA, CLOSURE_SCHEMA_CLOSED, CLOSURE_SCHEMA_OPEN)
    val s3Files = S3Files(S3Utils(s3Async(s3Endpoint)))
    val unexpectedFailureResponse = new APIGatewayProxyResponseEvent()
    unexpectedFailureResponse.setStatusCode(500)

    val requestHandler: IO[APIGatewayProxyResponseEvent] = for {
      draftMetadata <- IO(DraftMetadata(UUID.fromString(consignmentId)))
      _ <- s3Files.downloadFile(bucket, draftMetadata)
      // check UTF 8
      errorFileData <- validUTF(draftMetadata)
      // check CSV is a valid CSV file if no UTF-8 error
      errorFileData <- IO(errorFileData.orElse(validCSV(draftMetadata)))
      // if it is a valid csv then load the data
      csvData <- errorFileData.fold(l => loadCSV(draftMetadata), r => IO(List.empty[FileRow]))
      // validate required fields (using separate check as only one row required and want to change key identifier to consignmentID)
      // treat required fields as consignment problem not specific row of data)
      errorFileData <- IO(errorFileData.orElse(validateRequired(csvData, consignmentId)))
      // validate against schema if all required fields present
      errorFileData <- IO(errorFileData.orElse(validateMetadata(draftMetadata, csvData, schemaToValidate)))
      errorFilePath <- IO(writeErrorFileDataToFile(draftMetadata, errorFileData))
      // upload validation result file
      // maybe return emptyError list on success and handleError with an error
      _ <- s3Files.uploadFile(bucket, s"${draftMetadata.consignmentId}/$errorFileName", errorFilePath)
      // if no errors save metadata
      // maybe return emptyError list on success and handleError with an error
      _ <- IO(errorFileData.fold(l => persistMetadata(draftMetadata), r => r))
      // Check errorFileData and update the status accordingly
      _ <- updateStatus(errorFileData, draftMetadata)
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
  private def validateRequired(csvData: List[FileRow], consignmentID: String): Either[Unit, ErrorFileData] = {
    // TODO: To be implemented TDRD-62
    Left()
  }

  // Check file is UTF
  // maybe have new ValidationProcess.FILE_CHECK that can be used file integrity checks
  private def validUTF(draftMetadata: DraftMetadata): IO[Either[Unit, ErrorFileData]] = {
    // TODO: To be implemented TDRD-61
    IO(Left())
  }

  // Check file is a what is understood as a CSV header row and rows of data
  // maybe have new ValidationProcess.FILE_CHECK that can be used file integrity checks
  private def validCSV(draftMetadata: DraftMetadata): Either[Unit, ErrorFileData] = {
    // TODO: To be implemented TDRD-61
    Left()
  }

  private def extractConsignmentId(input: util.Map[String, Object]): String = {
    val inputParameters = input match {
      case stepFunctionInput if stepFunctionInput.containsKey("consignmentId") => stepFunctionInput
      case apiProxyRequestInput if apiProxyRequestInput.containsKey("pathParameters") =>
        apiProxyRequestInput.get("pathParameters").asInstanceOf[util.Map[String, Object]]
    }
    inputParameters.get("consignmentId").toString
  }

  private def validateMetadata(draftMetadata: DraftMetadata, csvData: List[FileRow], schema: Set[JsonSchemaDefinition]): Either[Unit, ErrorFileData] = {

    lazy val messageProperties = getMessageProperties()
    val validationErrors = MetadataValidationJsonSchema
      .validate(schema, csvData)
      .collect {
        case result if result._2.nonEmpty =>
          val errors = result._2.map(error => {
            val errorKey = s"${error.validationProcess}.${error.property}.${error.errorKey}"
            Error(
              error.validationProcess.toString,
              convertToAlternateKey(defaultAlternateKeyType, error.property) match {
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
      Right(ErrorFileData(draftMetadata, FileError.SCHEMA_VALIDATION, validationErrors))
    } else {
      Left()
    }
  }

  private def getMessageProperties(): Properties = {
    val source = Source.fromURL(getClass.getResource("/validation-messages/validation-messages.properties"))
    val properties = new Properties()
    properties.load(source.bufferedReader())
    properties
  }

  private def loadCSV(draftMetadata: DraftMetadata): IO[List[FileRow]] = {
    val csvHandler = new CSVHandler()
    val filePath = getFilePath(draftMetadata)
    IO(csvHandler.loadCSV(filePath))
  }

  private def updateStatus(errorFileData: Either[Unit, ErrorFileData], draftMetadata: DraftMetadata): IO[Option[Int]] = {
    val clientSecret = getClientSecret(clientSecretPath, endpoint)
    val status = errorFileData.fold(_ => "Completed", _ => "CompletedWithIssues")
    graphQlApi.updateConsignmentStatus(draftMetadata.consignmentId, clientSecret, "DraftMetadata", status)
  }

  private def persistMetadata(draftMetadata: DraftMetadata): Unit = {
    val clientSecret = getClientSecret(clientSecretPath, endpoint)
    val csvHandler = new CSVHandler()
    for {
      customMetadata <- graphQlApi.getCustomMetadata(draftMetadata.consignmentId, clientSecret)
      fileData <- IO(csvHandler.loadCSV(getFilePath(draftMetadata), getMetadataNames()))
      addOrUpdateBulkFileMetadata = MetadataUtils.filterProtectedFields(customMetadata, fileData)
      _ <- graphQlApi.addOrUpdateBulkFileMetadata(draftMetadata.consignmentId, clientSecret, addOrUpdateBulkFileMetadata)

    } yield ()
  }

  private def writeErrorFileDataToFile(draftMetadata: DraftMetadata, errorFileData: Either[Unit, ErrorFileData]): String = {
    val noError = ErrorFileData(draftMetadata)
    val json = errorFileData.getOrElse(noError).asJson.toString()
    val errorFilePath = getErrorFilePath(draftMetadata)
    Files.writeString(Paths.get(errorFilePath), json)
    errorFilePath
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
  case class DraftMetadata(consignmentId: UUID)
  def getFilePath(draftMetadata: DraftMetadata) = s"""$rootDirectory/${draftMetadata.consignmentId}/$fileName"""
  def getErrorFilePath(draftMetadata: DraftMetadata) = s"""$rootDirectory/${draftMetadata.consignmentId}/$errorFileName"""
  def getFolderPath(draftMetadata: DraftMetadata) = s"""$rootDirectory/${draftMetadata.consignmentId}"""
}
