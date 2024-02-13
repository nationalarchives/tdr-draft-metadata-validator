package uk.gov.nationalarchives

import cats.effect.IO
import com.amazonaws.services.lambda.runtime.{Context, RequestStreamHandler}
import com.typesafe.config.{ConfigFactory, Config => TypeSafeConfig}
import graphql.codegen.GetCustomMetadata.{customMetadata => cm}
import io.circe
import io.circe.generic.auto._
import io.circe.parser.decode
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.{S3AsyncClient, S3Client}
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, PutObjectRequest}
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.GetParameterRequest
import sttp.client3.{HttpURLConnectionBackend, Identity, SttpBackend}
import uk.gov.nationalarchives.Lambda.LambdaInput
import uk.gov.nationalarchives.aws.utils.s3.S3Clients._
import uk.gov.nationalarchives.aws.utils.s3.S3Utils
import uk.gov.nationalarchives.tdr.GraphQLClient
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}

import java.io.{InputStream, OutputStream}
import java.net.URI
import java.nio.file.Paths
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.io.Source


class Lambda {

  val configFactory: TypeSafeConfig = ConfigFactory.load
  val authUrl: String = configFactory.getString("auth.url")
  val apiUrl: String = configFactory.getString("api.url")
  val clientSecretPath: String = configFactory.getString("auth.clientSecretPath")
  val endpoint: String = configFactory.getString("ssm.endpoint")
  val s3Endpoint: String = configFactory.getString("s3.endpoint")
  val bucket: String = configFactory.getString("s3.draftMetadataBucket")
  val timeToLiveInSecs: Int = 60

  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()
  implicit val keycloakDeployment: TdrKeycloakDeployment = TdrKeycloakDeployment(authUrl, "tdr", timeToLiveInSecs)
  implicit def logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  val keycloakUtils = new KeycloakUtils()
  val customMetadataStatusClient = new GraphQLClient[cm.Data, cm.Variables](apiUrl)
  val graphQlApi: GraphQlApi = GraphQlApi(keycloakUtils, customMetadataStatusClient)

  def handleRequest(input: InputStream, output: OutputStream): Unit = {

    val rawInput: String = Source.fromInputStream(input).mkString
    val result: Either[circe.Error, LambdaInput] = decode[LambdaInput](rawInput)

    val customMetadata: Future[cm.Data] = result match {
      case Left(error) => throw new RuntimeException(error)
      case Right(lambdaInput) =>
        val decryptedSecret: String = getClientSecret(clientSecretPath, endpoint)
        val consignmentId = lambdaInput.consignmentId
        graphQlApi.getCustomMetadata(consignmentId, decryptedSecret)
    }

    val csvHandler = new CSVHandler()
    val s3Files = S3Files(S3Utils(s3Async(s3Endpoint)))

    val client: S3Client = s3(s3Endpoint)
    client.getObject(GetObjectRequest.builder.bucket(bucket).key("TDR-2024.csv").build, Paths.get("/tmp/TDR-2024.csv"))

    val res = for {
      cm <- customMetadata
      metadataValidation = MetadataValidationUtils.createMetadataValidation(cm.customMetadata)
    } yield {
//      s3Files.downloadFiles("TDR-2024.csv", bucket)
      val fileRows = csvHandler.loadCSV("TDR-2024.csv")
      val error = metadataValidation.validateMetadata(fileRows)
      val updatedFileRows = fileRows.map(file => file.metadata.map(_.value) :+ error(file.fileName).map(p => s"${p.propertyName}: ${p.errorCode}").mkString("|"))
      csvHandler.writeCsv(updatedFileRows ++ updatedFileRows, "TDR-2024.csv")
    }
    client.putObject(PutObjectRequest.builder.bucket(bucket).key("TDR-2024.csv").build, Paths.get("/tmp/TDR-2024.csv"))

    Await.result(res, 10.seconds)
  }

  private def getClientSecret(secretPath: String, endpoint: String): String = {
    val httpClient = ApacheHttpClient.builder.build
    val ssmClient: SsmClient = SsmClient.builder()
      .endpointOverride(URI.create(endpoint))
      .httpClient(httpClient)
      .region(Region.EU_WEST_2)
      .build()
    val getParameterRequest = GetParameterRequest.builder.name(secretPath).withDecryption(true).build
    ssmClient.getParameter(getParameterRequest).parameter().value()
  }
}


object Lambda {
  case class LambdaInput(consignmentId: UUID)
}
