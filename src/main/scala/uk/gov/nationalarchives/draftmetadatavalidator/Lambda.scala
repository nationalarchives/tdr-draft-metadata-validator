package uk.gov.nationalarchives.draftmetadatavalidator

import cats.effect.IO
import graphql.codegen.GetCustomMetadata.customMetadata.CustomMetadata
import graphql.codegen.GetCustomMetadata.{customMetadata => cm}
import graphql.codegen.GetDisplayProperties.displayProperties.DisplayProperties
import graphql.codegen.GetDisplayProperties.{displayProperties => dp}
import graphql.codegen.UpdateConsignmentStatus.{updateConsignmentStatus => ucs}
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
import scala.io.Source

class Lambda {

  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()
  implicit val keycloakDeployment: TdrKeycloakDeployment = TdrKeycloakDeployment(authUrl, "tdr", timeToLiveSecs)
  implicit def logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  val keycloakUtils = new KeycloakUtils()
  val customMetadataClient = new GraphQLClient[cm.Data, cm.Variables](apiUrl)
  val displayPropertiesClient = new GraphQLClient[dp.Data, dp.Variables](apiUrl)
  val updateConsignmentStatusClient = new GraphQLClient[ucs.Data, ucs.Variables](apiUrl)
  val graphQlApi: GraphQlApi = GraphQlApi(keycloakUtils, customMetadataClient, updateConsignmentStatusClient, displayPropertiesClient)

  def handleRequest(input: InputStream, output: OutputStream): Unit = {
    val body: String = Source.fromInputStream(input).mkString
    val s3Files = S3Files(S3Utils(s3Async(s3Endpoint)))

    for {
      draftMetadata <- IO.fromEither(decode[DraftMetadata](body))
      _ <- s3Files.downloadFile(bucket, draftMetadata)
      hasErrors <- validateMetadata(draftMetadata)
      _ <- if (hasErrors) s3Files.uploadFile(bucket, draftMetadata) else IO.unit
    } yield ()
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
          val updatedFileRows = fileData.fileRows.map(file => {
            List(file.fileName) ++ file.metadata.map(_.value) ++ List(errors(file.fileName).map(p => s"${p.propertyName}: ${p.errorCode}").mkString(" | "))
          })
          csvHandler.writeCsv((fileData.header :+ "Error") :: updatedFileRows, filePath)
          graphQlApi
            .updateConsignmentStatus(draftMetadata.consignmentId, clientSecret, "DraftMetadata", "CompletedWithIssues")
            .map(_ => true)
        } else {
          // This would be where the valid metadata would be saved to the DB
          graphQlApi
            .updateConsignmentStatus(draftMetadata.consignmentId, clientSecret, "DraftMetadata", "Completed")
            .map(_ => false)
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
    def getStringValue: String = {
      attribute match {
        case Some(a) => a.value.getOrElse("")
        case _       => ""
      }
    }

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
