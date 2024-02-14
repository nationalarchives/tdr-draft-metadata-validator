package uk.gov.nationalarchives.draftmetadatavalidator

import cats.effect.IO
import graphql.codegen.GetCustomMetadata.{customMetadata => cm}
import io.circe.generic.auto._
import io.circe.parser.decode
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

import java.io.{InputStream, OutputStream}
import java.net.URI
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.io.Source

class Lambda {

  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()
  implicit val keycloakDeployment: TdrKeycloakDeployment = TdrKeycloakDeployment(authUrl, "tdr", timeToLiveInSecs)
  implicit def logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  val keycloakUtils = new KeycloakUtils()
  val customMetadataStatusClient = new GraphQLClient[cm.Data, cm.Variables](apiUrl)
  val graphQlApi: GraphQlApi = GraphQlApi(keycloakUtils, customMetadataStatusClient)

  def handleRequest(input: InputStream, output: OutputStream): Unit = {
    val body: String = Source.fromInputStream(input).mkString
    val s3Files = S3Files(S3Utils(s3Async(s3Endpoint)))

    for {
      draftMetadata <- IO.fromEither(decode[DraftMetadata](body))
      _ <- s3Files.downloadFile(bucket, draftMetadata)
      _ <- validateMetadata(draftMetadata)
      output <- s3Files.uploadFiles(bucket, draftMetadata)
    } yield output
  }.unsafeRunSync()(cats.effect.unsafe.implicits.global)

  private def validateMetadata(draftMetadata: DraftMetadata): IO[Unit] = {
    for {
      cm <- graphQlApi.getCustomMetadata(draftMetadata.consignmentId, getClientSecret(clientSecretPath, endpoint)).toIO
      metadataValidation = MetadataValidationUtils.createMetadataValidation(cm.customMetadata)
    } yield {
      val csvHandler = new CSVHandler()
      val filePath = getFilePath(draftMetadata)
      val fileData = csvHandler.loadCSV(filePath)
      val error = metadataValidation.validateMetadata(fileData.fileRows)
      if (error.isEmpty) {
        IO.unit
      } else {
        val updatedFileRows = fileData.fileRows.map(file => {
          List(file.fileName) ++ file.metadata.map(_.value) ++ List(error(file.fileName).map(p => s"${p.propertyName}: ${p.errorCode}").mkString(" | "))
        })
        csvHandler.writeCsv((fileData.header :+ "Error") :: updatedFileRows, filePath)
      }
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

  implicit class FutureUtils[T](f: Future[T]) {
    def toIO: IO[T] = IO.fromFuture(IO(f))
  }
}

object Lambda {
  case class DraftMetadata(consignmentId: UUID, fileName: String)
  def getFilePath(draftMetadata: DraftMetadata) = s"""${rootDirectory}/${draftMetadata.consignmentId}/${draftMetadata.fileName}"""
}
