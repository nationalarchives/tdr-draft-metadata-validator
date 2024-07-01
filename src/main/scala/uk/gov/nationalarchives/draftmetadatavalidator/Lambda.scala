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
import uk.gov.nationalarchives.draftmetadatavalidator.Lambda.DraftMetadata
import uk.gov.nationalarchives.tdr.GraphQLClient
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}
import uk.gov.nationalarchives.tdr.validation.FileRow
import uk.gov.nationalarchives.tdr.validation.schema.JsonSchemaDefinition.BASE_SCHEMA
import uk.gov.nationalarchives.tdr.validation.schema.MetadataValidationJsonSchema
import uk.gov.nationalarchives.tdr.validation.schema.MetadataValidationJsonSchema.ObjectMetadata

import java.net.URI
import java.util
import java.util.UUID
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
    val consignmentId = extractInputParameter("consignmentId", input)
    val fileName = extractInputParameter("fileName", input)
    val s3Files = S3Files(S3Utils(s3Async(s3Endpoint)))
    for {
      draftMetadata <- IO(DraftMetadata(UUID.fromString(consignmentId), fileName))
      _ <- s3Files.downloadFile(bucket, draftMetadata)
      hasErrors <- validateMetadata(draftMetadata)
      _ <- if (hasErrors) s3Files.uploadFile(bucket, draftMetadata) else IO.unit
    } yield {
      val response = new APIGatewayProxyResponseEvent()
      response.setStatusCode(200)
      response
    }
  }.unsafeRunSync()(cats.effect.unsafe.implicits.global)

  private def extractInputParameter(inputParameterName: String, input: util.Map[String, Object]): String = {
    val inputParameters = input match {
      case stepFunctionInput if stepFunctionInput.containsKey(s"$inputParameterName") => stepFunctionInput
      case apiProxyRequestInput if apiProxyRequestInput.containsKey("pathParameters") =>
        apiProxyRequestInput.get("pathParameters").asInstanceOf[util.Map[String, Object]]
    }
    inputParameters.get(s"$inputParameterName").toString
  }

  private def validateMetadata(draftMetadata: DraftMetadata): IO[Boolean] = {
    val clientSecret = getClientSecret(clientSecretPath, endpoint)
    val persistMetadataHandler = DataPersistenceHandler.apply(draftMetadata, clientSecret, graphQlApi)
    for {
      customMetadata <- graphQlApi.getCustomMetadata(draftMetadata.consignmentId, clientSecret)
      displayProperties <- graphQlApi.getDisplayProperties(draftMetadata.consignmentId, clientSecret)
      result <- {
        val csvHandler = new CSVHandler()
        val filePath = draftMetadata.filePath
        // Loading CSV twice as validation and writing of CSV currently done using different style
        // The important fact is the .fileName that is used to match errors to rows written.
        // Currently using last column UUID. If it is decided to use the UUID the 'fileName' attribute
        // should be renamed
        val fileData: FileData = csvHandler.loadCSV(filePath, getMetadataNames(displayProperties, customMetadata))
        val fileRows: List[FileRow] = csvHandler.loadCSV(filePath)
        val errors = if (draftMetadata.dataLoad) {
          MetadataValidationJsonSchema.validate(BASE_SCHEMA, fileRows.map(fr => ObjectMetadata(fr.fileName, fr.metadata.toSet)).toSet)
        } else {
          MetadataValidationJsonSchema.validate(fileRows)
        }
        if (errors.values.exists(_.nonEmpty)) {
          val updatedFileRows = "Error" :: fileData.fileRows.map(file => {
            errors(file.fileName).map(p => s"${p.propertyName}: ${p.errorCode}").mkString(" | ")
          })
          csvHandler.writeCsv(fileData.allRowsWithHeader.zip(updatedFileRows).map(p => p._1 :+ p._2), filePath)
          graphQlApi
            .updateConsignmentStatus(draftMetadata.consignmentId, clientSecret, "DraftMetadata", "CompletedWithIssues")
            .map(_ => true)
        } else {
          val rows = if (draftMetadata.dataLoad) fileRows else fileData.fileRows
          persistMetadataHandler.persistValidMetadata(rows, customMetadata)
        }
      }
    } yield {
      result
    }
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
  case class DraftMetadata(consignmentId: UUID, fileName: String) {
    val dataLoad: Boolean = fileName == dataLoadFileName
    val bucketKey: String = if (dataLoad) { s"$consignmentId/dataload/$fileName" }
    else { s"$consignmentId/$fileName" }
    val filePath: String = if (dataLoad) { s"$rootDirectory/$consignmentId/dataload/$fileName" }
    else { s"$rootDirectory/$consignmentId/$fileName" }
    val folderPath: String = if (dataLoad) { s"$rootDirectory/$consignmentId/dataload" }
    else { s"$rootDirectory/$consignmentId" }
  }
}
