package uk.gov.nationalarchives.draftmetadatavalidator

import cats.effect.IO
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import graphql.codegen.GetCustomMetadata.customMetadata.CustomMetadata
import graphql.codegen.GetCustomMetadata.{customMetadata => cm}
import graphql.codegen.GetDisplayProperties.displayProperties.DisplayProperties
import graphql.codegen.GetDisplayProperties.{displayProperties => dp}
import graphql.codegen.UpdateConsignmentStatus.{updateConsignmentStatus => ucs}
import graphql.codegen.AddOrUpdateBulkFileMetadata.{addOrUpdateBulkFileMetadata => afm}
import graphql.codegen.types.DataType._
import graphql.codegen.types.{AddOrUpdateFileMetadata, AddOrUpdateMetadata}
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
import uk.gov.nationalarchives.draftmetadatavalidator.Lambda.{DraftMetadata, getFilePath}
import uk.gov.nationalarchives.tdr.GraphQLClient
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}
import uk.gov.nationalarchives.tdr.validation.Metadata

import java.io.File
import java.net.URI
import java.sql.Timestamp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.io.Directory

class Lambda {

  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()
  implicit val keycloakDeployment: TdrKeycloakDeployment = TdrKeycloakDeployment(authUrl, "tdr", timeToLiveSecs)
  implicit def logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  private val keycloakUtils = new KeycloakUtils()
  private val customMetadataClient = new GraphQLClient[cm.Data, cm.Variables](apiUrl)
  private val displayPropertiesClient = new GraphQLClient[dp.Data, dp.Variables](apiUrl)
  private val updateConsignmentStatusClient = new GraphQLClient[ucs.Data, ucs.Variables](apiUrl)
  private val addOrUpdateBulkFileMetadataClient = new GraphQLClient[afm.Data, afm.Variables](apiUrl)
  private val graphQlApi: GraphQlApi = GraphQlApi(keycloakUtils, customMetadataClient, updateConsignmentStatusClient, addOrUpdateBulkFileMetadataClient, displayPropertiesClient)

  def handleRequest(event: APIGatewayProxyRequestEvent, context: Context): APIGatewayProxyResponseEvent = {
    val pathParam = event.getPathParameters

    cleanupRootDirectory()
    val s3Files = S3Files(S3Utils(s3Async(s3Endpoint)))
    for {
      draftMetadata <- IO(DraftMetadata(UUID.fromString(pathParam.get("consignmentId"))))
      _ <- s3Files.downloadFile(bucket, draftMetadata)
      hasErrors <- validateMetadata(draftMetadata)
      _ <- if (hasErrors) s3Files.uploadFile(bucket, draftMetadata) else IO.unit
    } yield {
      val response = new APIGatewayProxyResponseEvent()
      response.setStatusCode(200)
      response
    }
  }.unsafeRunSync()(cats.effect.unsafe.implicits.global)

  private def validateMetadata(draftMetadata: DraftMetadata): IO[Boolean] = {
    val clientSecret = getClientSecret(clientSecretPath, endpoint)
    for {
      customMetadata <- graphQlApi.getCustomMetadata(draftMetadata.consignmentId, clientSecret)
      displayProperties <- graphQlApi.getDisplayProperties(draftMetadata.consignmentId, clientSecret)
      metadataValidator = MetadataValidationUtils.createMetadataValidation(customMetadata)
      result <- {
        val csvHandler = new CSVHandler()
        val filePath = getFilePath(draftMetadata)
        val fileData = csvHandler.loadCSV(filePath, getMetadataNames(displayProperties, customMetadata))
        val errors = metadataValidator.validateMetadata(fileData.fileRows)
        if (errors.values.exists(_.nonEmpty)) {
          val updatedFileRows = "Error" :: fileData.fileRows.map(file => {
            errors(file.fileName).map(p => s"${p.propertyName}: ${p.errorCode}").mkString(" | ")
          })
          csvHandler.writeCsv(fileData.allRowsWithHeader.zip(updatedFileRows).map(p => p._1 :+ p._2), filePath)
          graphQlApi
            .updateConsignmentStatus(draftMetadata.consignmentId, clientSecret, "DraftMetadata", "CompletedWithIssues")
            .map(_ => true)
        } else {
          val addOrUpdateBulkFileMetadata = convertDataToBulkFileMetadataInput(fileData, customMetadata)
          for {
            _ <- graphQlApi.addOrUpdateBulkFileMetadata(draftMetadata.consignmentId, clientSecret, addOrUpdateBulkFileMetadata)
            - <- graphQlApi.updateConsignmentStatus(draftMetadata.consignmentId, clientSecret, "DraftMetadata", "Completed")
          } yield false
        }
      }
    } yield {
      result
    }
  }

  private def convertDataToBulkFileMetadataInput(fileData: FileData, customMetadata: List[CustomMetadata]): List[AddOrUpdateFileMetadata] = {
    fileData.fileRows.collect {
      case fileRow if fileRow.metadata.exists(_.value.nonEmpty) =>
        AddOrUpdateFileMetadata(
          UUID.fromString(fileRow.fileName),
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

  private def cleanupRootDirectory() = {
    val directory = new Directory(new File(rootDirectory))
    directory.deleteRecursively()
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
  def getFilePath(draftMetadata: DraftMetadata) = s"""${rootDirectory}/${draftMetadata.consignmentId}/$fileName"""
}
