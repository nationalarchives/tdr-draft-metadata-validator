package uk.gov.nationalarchives.draftmetadatavalidator

import cats.effect.IO
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent
import com.amazonaws.services.lambda.runtime.{Context, RequestHandler}
import graphql.codegen.AddFilesAndMetadata.{addFilesAndMetadata => af}
import graphql.codegen.AddOrUpdateBulkFileMetadata.{addOrUpdateBulkFileMetadata => afm}
import graphql.codegen.GetCustomMetadata.customMetadata.CustomMetadata
import graphql.codegen.GetCustomMetadata.{customMetadata => cm}
import graphql.codegen.GetDisplayProperties.displayProperties.DisplayProperties
import graphql.codegen.GetDisplayProperties.{displayProperties => dp}
import graphql.codegen.UpdateConsignmentStatus.{updateConsignmentStatus => ucs}
import graphql.codegen.types.DataType._
import graphql.codegen.types.{AddOrUpdateFileMetadata, AddOrUpdateMetadata, ClientSideMetadataInput}
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
import uk.gov.nationalarchives.tdr.validation.schema.MetadataValidationJsonSchema.ObjectMetadata
import uk.gov.nationalarchives.tdr.validation.schema.{JsonSchemaDefinition, MetadataValidationJsonSchema}
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}

import java.net.URI
import java.sql.Timestamp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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
  private val addFilesAndMetadataClient = new GraphQLClient[af.Data, af.Variables](apiUrl)
  private val graphQlApi: GraphQlApi =
    GraphQlApi(keycloakUtils, customMetadataClient, updateConsignmentStatusClient, addOrUpdateBulkFileMetadataClient, displayPropertiesClient, addFilesAndMetadataClient)

  def handleRequest(input: java.util.Map[String, Object], context: Context): APIGatewayProxyResponseEvent = {
    // Need something on the input to indicate data load metadata
    val fileName = extractInputParameter("fileName", input)
    val dataLoad = dataLoadMetadata(fileName)
    val consignmentId = extractInputParameter("consignmentId", input)
    val s3Files = S3Files(S3Utils(s3Async(s3Endpoint)))
    for {
      draftMetadata <- IO(DraftMetadata(UUID.fromString(consignmentId), Some(fileName)))
      _ <- s3Files.downloadFile(bucket, draftMetadata)
      hasErrors <- if (dataLoad) validateDataLoadMetadata(draftMetadata) else validateMetadata(draftMetadata)
      _ <- if (hasErrors && !dataLoad) s3Files.uploadFile(bucket, draftMetadata) else IO.unit
    } yield {
      val response = new APIGatewayProxyResponseEvent()
      response.setStatusCode(200)
      response
    }
  }.unsafeRunSync()(cats.effect.unsafe.implicits.global)

  private def dataLoadMetadata(inputFileName: String):Boolean = {
    inputFileName == dataLoadFileName
  }

  private def validateDataLoadMetadata(draftMetadata: DraftMetadata): IO[Boolean] = {
    val clientSecret = getClientSecret(clientSecretPath, endpoint)
    for {
      customMetadata <- graphQlApi.getCustomMetadata(draftMetadata.consignmentId, clientSecret)
      result <- {
        val csvHandler = new CSVHandler()
        val filePath = getFilePath(draftMetadata)
        val fileRows: List[FileRow] = csvHandler.loadCSV(filePath)
        val objectMetadata: Set[ObjectMetadata] = fileRows.map(fileRow => ObjectMetadata(fileRow.fileName, fileRow.metadata.toSet)).toSet
        // Use the 'dataload' schema when available
        val errors = MetadataValidationJsonSchema.validate(JsonSchemaDefinition.BASE_SCHEMA, objectMetadata)
        if (errors.values.exists(_.nonEmpty)) {
          // This will need to handle additional metadata error -> save new csv back to get in state for user continuing journey in TDR
          // other errors
          graphQlApi
            .updateConsignmentStatus(draftMetadata.consignmentId, clientSecret, "DataLoadMetadata", "CompletedWithIssues")
            .map(_ => true)
        } else {
          val inputs = convertRowsToInput(fileRows, customMetadata)
          for {
            // Still need to handle the undefined metadata
            _ <- graphQlApi.addFileEntriesAndMetadata(draftMetadata.consignmentId, clientSecret, inputs.map(_._1))
            _ <- graphQlApi.addOrUpdateBulkFileMetadata(draftMetadata.consignmentId, clientSecret, inputs.map(_._2))
            - <- graphQlApi.updateConsignmentStatus(draftMetadata.consignmentId, clientSecret, "DataLoadMetadata", "Completed")
          } yield false
        }
      }
    } yield {
      result
    }
  }

  private def extractInputParameter(inputParameterName: String, input: util.Map[String, Object]): String = {
    val inputParameters = input match {
      case stepFunctionInput if stepFunctionInput.containsKey(inputParameterName) => stepFunctionInput
      case apiProxyRequestInput if apiProxyRequestInput.containsKey("pathParameters") =>
        apiProxyRequestInput.get("pathParameters").asInstanceOf[util.Map[String, Object]]
    }
    inputParameters.get(inputParameterName).toString
  }

  private def validateMetadata(draftMetadata: DraftMetadata): IO[Boolean] = {
    val clientSecret = getClientSecret(clientSecretPath, endpoint)
    for {
      customMetadata <- graphQlApi.getCustomMetadata(draftMetadata.consignmentId, clientSecret)
      displayProperties <- graphQlApi.getDisplayProperties(draftMetadata.consignmentId, clientSecret)
      result <- {
        val csvHandler = new CSVHandler()
        val filePath = getFilePath(draftMetadata)
        // Loading CSV twice as validation and writing of CSV currently done using different style
        // The important fact is the .fileName that is used to match errors to rows written.
        // Currently using last column UUID. If it is decided to use the UUID the 'fileName' attribute
        // should be renamed
        val fileData: FileData = csvHandler.loadCSV(filePath, getMetadataNames(displayProperties, customMetadata))
        val fileRows: List[FileRow] = csvHandler.loadCSV(filePath)
        val errors = MetadataValidationJsonSchema.validate(fileRows)
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
        convertFileRowMetadataToInput(fileRow.fileName, fileRow, customMetadata)
    }
  }

  private val clientSideMetadataProperties: Set[String] = Set("clientside_original_filepath", "file_size", "Checksum", "date_last_modified")

  private def convertRowsToInput(input: List[FileRow], customMetadata: List[CustomMetadata]): List[(ClientSideMetadataInput, AddOrUpdateFileMetadata, List[Metadata])] = {
    input.map(fileRow => {
      val metadata = fileRow.metadata
      val definedMetadata = customMetadata.map(_.name).toSet
      val undefinedMetadata = metadata.filter(m => !definedMetadata.contains(m.name) && !clientSideMetadataProperties.contains(m.name))
      val fileId = metadata.filter(_.name == "UUID").head.value
      val clientSideOriginalPath = metadata.filter(_.name == "clientside_original_filepath").head.value
      val fileSize = metadata.filter(_.name == "file_size").head.value.toLong
      val checksum = metadata.filter(_.name == "Checksum").head.value
      val dateLastModified = metadata.filter(_.name == "date_last_modified").head.value.toLong

      val clientSideMetadataInput = ClientSideMetadataInput(clientSideOriginalPath, checksum, dateLastModified, fileSize, 1)
      val fileMetadataInput = convertFileRowMetadataToInput(fileId, fileRow, customMetadata)

      (clientSideMetadataInput, fileMetadataInput, undefinedMetadata)
    })
  }

  private def convertFileRowMetadataToInput(fileId: String, fileRow: FileRow, customMetadata: List[CustomMetadata]): AddOrUpdateFileMetadata = {
    AddOrUpdateFileMetadata(
      UUID.fromString(fileId),
      fileRow.metadata.collect { case m if m.value.nonEmpty => createAddOrUpdateMetadata(m, customMetadata.find(_.name == m.name).get) }.flatten
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
  case class DraftMetadata(consignmentId: UUID, otherFile: Option[String] = None)
  def getFilePath(draftMetadata: DraftMetadata): String = {
    val nameOfFile = draftMetadata.otherFile.getOrElse(fileName)
    s"""${rootDirectory}/${draftMetadata.consignmentId}/$nameOfFile"""
  }

  def getFolderPath(draftMetadata: DraftMetadata) = s"""${rootDirectory}/${draftMetadata.consignmentId}"""
}
