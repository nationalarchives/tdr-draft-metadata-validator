package uk.gov.nationalarchives.tdr.draftmetadatapersistor

import cats.effect.IO
import cats.effect.std.Semaphore
import cats.implicits.{catsStdInstancesForList, catsSyntaxParallelTraverse1}
import com.amazonaws.services.lambda.runtime.Context
import graphql.codegen.AddOrUpdateBulkFileMetadata.addOrUpdateBulkFileMetadata.AddOrUpdateBulkFileMetadata
import graphql.codegen.AddOrUpdateBulkFileMetadata.{addOrUpdateBulkFileMetadata => afm}
import graphql.codegen.GetCustomMetadata.{customMetadata => cm}
import graphql.codegen.GetFilesWithUniqueAssetIdKey.{getFilesWithUniqueAssetIdKey => uaik}
import graphql.codegen.UpdateConsignmentMetadataSchemaLibraryVersion.{updateConsignmentMetadataSchemaLibraryVersion => ucslv}
import graphql.codegen.types.AddOrUpdateFileMetadata
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.GetParameterRequest
import sttp.client3.{HttpURLConnectionBackend, Identity, SttpBackend, SttpBackendOptions}
import Lambda.{MetadataPersistorParameters, getFilePath}
import uk.gov.nationalarchives.aws.utils.s3.S3Clients.s3Async
import uk.gov.nationalarchives.aws.utils.s3.S3Utils
import uk.gov.nationalarchives.draftmetadata.config.ApplicationConfig._
import uk.gov.nationalarchives.draftmetadata.csv.CSVHandler
import uk.gov.nationalarchives.draftmetadata.s3.S3Files
import uk.gov.nationalarchives.draftmetadata.utils.MetadataUtils
import uk.gov.nationalarchives.tdr.GraphQLClient
import uk.gov.nationalarchives.tdr.draftmetadatapersistor.grapgql.{FileDetail, GraphQlApi}
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}
import uk.gov.nationalarchives.tdr.schemautils.ConfigUtils

import java.net.URI
import java.util
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.jdk.CollectionConverters.MapHasAsJava

class Lambda {

  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend(options = SttpBackendOptions.connectionTimeout(graphqlApiRequestTimeOut))
  implicit val keycloakDeployment: TdrKeycloakDeployment = TdrKeycloakDeployment(authUrl, "tdr", timeToLiveSecs)
  implicit def logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]
  implicit val metadataConfiguration: ConfigUtils.MetadataConfiguration = ConfigUtils.loadConfiguration

  private val keycloakUtils = new KeycloakUtils()
  private val customMetadataClient = new GraphQLClient[cm.Data, cm.Variables](apiUrl)
  private val addOrUpdateBulkFileMetadataClient = new GraphQLClient[afm.Data, afm.Variables](apiUrl)
  private val updateMetadataSchemaLibraryVersionClient = new GraphQLClient[ucslv.Data, ucslv.Variables](apiUrl)
  private val getFilesWithUniqueAssetIdKey = new GraphQLClient[uaik.Data, uaik.Variables](apiUrl)

  private val graphQlApi: GraphQlApi = GraphQlApi(
    keycloakUtils,
    customMetadataClient,
    addOrUpdateBulkFileMetadataClient,
    getFilesWithUniqueAssetIdKey,
    updateMetadataSchemaLibraryVersionClient
  )

  def handleRequest(input: java.util.Map[String, Object], context: Context): java.util.Map[String, Object] = {

    val metadataPersistorParameters: MetadataPersistorParameters = extractMetadataPersistorParameters(input)

    val s3Files = S3Files(S3Utils(s3Async(s3Endpoint)))

    val resultIO = for {
      filesWithUniqueAssetIdKey <- graphQlApi.getFilesWithUniqueAssetIdKey(metadataPersistorParameters.consignmentId, getClientSecret(clientSecretPath, endpoint))
      _ <- s3Files.downloadFile(bucket, metadataPersistorParameters.consignmentId.toString)
      _ <- persistMetadata(metadataPersistorParameters, filesWithUniqueAssetIdKey)
      _ <- updateConsignmentMetadataSchemaLibraryVersion(metadataPersistorParameters.consignmentId, metadataPersistorParameters.metadataSchemaLibraryVersion)
    } yield ()

    resultIO.unsafeRunSync()(cats.effect.unsafe.implicits.global)

    Map[String, Object](
      "consignmentId" -> metadataPersistorParameters.consignmentId.toString
    ).asJava
  }

  private def extractMetadataPersistorParameters(input: util.Map[String, Object]): MetadataPersistorParameters = {
    val inputParameters = input match {
      case stepFunctionInput if stepFunctionInput.containsKey("consignmentId") => stepFunctionInput
      case apiProxyRequestInput if apiProxyRequestInput.containsKey("pathParameters") =>
        apiProxyRequestInput.get("pathParameters").asInstanceOf[util.Map[String, Object]]
    }

    MetadataPersistorParameters(
      consignmentId = UUID.fromString(inputParameters.get("consignmentId").toString),
      uniqueAssetIdKey = inputParameters.getOrDefault("uniqueAssetIdKey", "file_path").toString,
      clientAlternateKey = inputParameters.getOrDefault("clientAlternateKey", "tdrFileHeader").toString,
      persistenceAlternateKey = inputParameters.getOrDefault("persistenceAlternateKey", "tdrDataLoadHeader").toString,
      metadataSchemaLibraryVersion = inputParameters.getOrDefault("metadataSchemaLibraryVersion", "Not provided").toString
    )
  }

  private def updateConsignmentMetadataSchemaLibraryVersion(consignmentId: UUID, metadataSchemaLibraryVersion: String): IO[Option[Int]] = {
    val clientSecret = getClientSecret(clientSecretPath, endpoint)
    graphQlApi.updateConsignmentMetadataSchemaLibraryVersion(
      consignmentId,
      clientSecret,
      metadataSchemaLibraryVersion
    )
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

  private def persistMetadata(draftMetadata: MetadataPersistorParameters, filesWithUniqueAssetIdKey: Map[String, FileDetail]): IO[List[AddOrUpdateBulkFileMetadata]] = {
    val clientSecret = getClientSecret(clientSecretPath, endpoint)
    for {
      customMetadata <- graphQlApi.getCustomMetadata(draftMetadata.consignmentId, clientSecret)
      fileData <- IO(CSVHandler.loadCSV(getFilePath(draftMetadata), draftMetadata.clientAlternateKey, draftMetadata.persistenceAlternateKey, draftMetadata.uniqueAssetIdKey))
      addOrUpdateBulkFileMetadata = MetadataUtils.filterProtectedFields(customMetadata, fileData, filesWithUniqueAssetIdKey)(_.fileId)
      result <- writeMetadataToDatabase(draftMetadata.consignmentId, clientSecret, addOrUpdateBulkFileMetadata)
    } yield result
  }

  private def writeMetadataToDatabase(consignmentId: UUID, clientSecret: String, metadata: List[AddOrUpdateFileMetadata]): IO[List[AddOrUpdateBulkFileMetadata]] = {
    Semaphore[IO](maxConcurrencyForMetadataDatabaseWrites).flatMap { semaphore =>
      metadata
        .grouped(batchSizeForMetadataDatabaseWrites)
        .toList
        .parTraverse { mdGroup =>
          semaphore.permit.use { _ =>
            graphQlApi.addOrUpdateBulkFileMetadata(consignmentId, clientSecret, mdGroup)
          }
        }
        .map(_.flatten)
    }
  }
}

object Lambda {

  def getFilePath(draftMetadata: MetadataPersistorParameters) = s"""$rootDirectory/${draftMetadata.consignmentId}/$fileName"""

  case class MetadataPersistorParameters(
      consignmentId: UUID,
      uniqueAssetIdKey: String,
      clientAlternateKey: String,
      persistenceAlternateKey: String,
      metadataSchemaLibraryVersion: String = "version_1_0_0"
  )
}
