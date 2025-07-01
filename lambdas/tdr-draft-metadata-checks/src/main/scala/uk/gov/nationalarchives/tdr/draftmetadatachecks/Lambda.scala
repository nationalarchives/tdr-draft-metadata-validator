package uk.gov.nationalarchives.tdr.draftmetadatachecks

import cats.effect.IO
import cats.effect.std.Semaphore
import cats.syntax.semigroup._
import cats.implicits.{catsStdInstancesForList, catsSyntaxParallelTraverse1}
import com.amazonaws.services.lambda.runtime.Context
import graphql.codegen.AddOrUpdateBulkFileMetadata.addOrUpdateBulkFileMetadata.AddOrUpdateBulkFileMetadata
import graphql.codegen.AddOrUpdateBulkFileMetadata.{addOrUpdateBulkFileMetadata => afm}
import graphql.codegen.GetCustomMetadata.{customMetadata => cm}
import graphql.codegen.GetFilesWithUniqueAssetIdKey.{getFilesWithUniqueAssetIdKey => uaik}
import graphql.codegen.UpdateConsignmentMetadataSchemaLibraryVersion.{updateConsignmentMetadataSchemaLibraryVersion => ucslv}
import graphql.codegen.UpdateConsignmentStatus.{updateConsignmentStatus => ucs}
import graphql.codegen.types.AddOrUpdateFileMetadata
import io.circe.Encoder
import io.circe.generic.auto._
import io.circe.syntax._
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.GetParameterRequest
import sttp.client3.{HttpURLConnectionBackend, Identity, SttpBackend, SttpBackendOptions}
import uk.gov.nationalarchives.aws.utils.s3.S3Clients._
import uk.gov.nationalarchives.aws.utils.s3.S3Utils
import ApplicationConfig._
import FileError.PROTECTED_FIELD
import Lambda.{ValidationExecutionError, ValidationParameters, getErrorFilePath, getFilePath}
import ValidationErrors._
import uk.gov.nationalarchives.tdr.draftmetadatachecks.validations.FOIClosureCodesAndPeriods.foiCodesPeriodsConsistent
import uk.gov.nationalarchives.tdr.GraphQLClient
import uk.gov.nationalarchives.tdr.draftmetadatachecks.utils.{DependencyVersionReader, MetadataUtils}
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}
import uk.gov.nationalarchives.tdr.schemautils.ConfigUtils
import uk.gov.nationalarchives.tdr.validation.schema.JsonSchemaDefinition._
import uk.gov.nationalarchives.tdr.validation.schema.{JsonSchemaDefinition, MetadataValidationJsonSchema}
import uk.gov.nationalarchives.tdr.validation.{FileRow, Metadata}
import uk.gov.nationalarchives.utf8.validator.Utf8Validator

import java.io.FileInputStream
import java.net.URI
import java.nio.file.{Files, Paths}
import java.util
import java.util.{Properties, UUID}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.io.Source
import scala.jdk.CollectionConverters.MapHasAsJava
import scala.util.{Failure, Try}

class Lambda {

  implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend(options = SttpBackendOptions.connectionTimeout(graphqlApiRequestTimeOut))
  implicit val keycloakDeployment: TdrKeycloakDeployment = TdrKeycloakDeployment(authUrl, "tdr", timeToLiveSecs)
  implicit def logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]
  implicit val fileErrorEncoder: Encoder[FileError.Value] = Encoder.encodeEnumeration(FileError)
  private lazy val messageProperties = getMessageProperties
  implicit val metadataConfiguration: ConfigUtils.MetadataConfiguration = ConfigUtils.loadConfiguration

  private val keycloakUtils = new KeycloakUtils()
  private val customMetadataClient = new GraphQLClient[cm.Data, cm.Variables](apiUrl)
  private val updateConsignmentStatusClient = new GraphQLClient[ucs.Data, ucs.Variables](apiUrl)
  private val addOrUpdateBulkFileMetadataClient = new GraphQLClient[afm.Data, afm.Variables](apiUrl)
  private val updateMetadataSchemaLibraryVersionClient = new GraphQLClient[ucslv.Data, ucslv.Variables](apiUrl)
  private val getFilesWithUniqueAssetIdKey = new GraphQLClient[uaik.Data, uaik.Variables](apiUrl)

  private val graphQlApi: GraphQlApi = GraphQlApi(
    keycloakUtils,
    customMetadataClient,
    updateConsignmentStatusClient,
    addOrUpdateBulkFileMetadataClient,
    getFilesWithUniqueAssetIdKey,
    updateMetadataSchemaLibraryVersionClient
  )

  def handleRequest(input: java.util.Map[String, Object], context: Context): java.util.Map[String, Object] = {
    val startTime = System.currentTimeMillis()
    val consignmentId = extractConsignmentId(input)
    val schemaToValidate: Set[JsonSchemaDefinition] = Set(BASE_SCHEMA, CLOSURE_SCHEMA_CLOSED, CLOSURE_SCHEMA_OPEN, RELATIONSHIP_SCHEMA)
    val validationParameters: ValidationParameters = ValidationParameters(
      consignmentId = UUID.fromString(consignmentId),
      schemaToValidate = schemaToValidate,
      uniqueAssetIdKey = "file_path",
      clientAlternateKey = "tdrFileHeader",
      persistenceAlternateKey = "tdrDataLoadHeader",
      expectedPropertyField = "expectedTDRHeader",
      requiredSchema = Some(REQUIRED_SCHEMA)
    )

    val resultIO = for {
      _ <- logger.info(s"Metadata validation was run for $consignmentId")
      filesWithUniqueAssetIdKey <- graphQlApi.getFilesWithUniqueAssetIdKey(UUID.fromString(consignmentId), getClientSecret(clientSecretPath, endpoint))
      errorFileData <- doValidation(validationParameters, filesWithUniqueAssetIdKey)
      _ <- writeErrorFileDataToFile(validationParameters, errorFileData)
      _ <- if (errorFileData.validationErrors.isEmpty) persistMetadata(validationParameters, filesWithUniqueAssetIdKey) else IO.unit
      _ <- updateConsignmentMetadataSchemaLibraryVersion(validationParameters)
      _ <- updateStatus(errorFileData, validationParameters)
    } yield ()

    resultIO.unsafeRunSync()(cats.effect.unsafe.implicits.global)
    Map[String, Object](
      "consignmentId" -> consignmentId,
      "validationTime" -> s"${(System.currentTimeMillis() - startTime) / 1000.0} seconds"
    ).asJava
  }

  private def doValidation(validationParameters: ValidationParameters, filesWithUniqueAssetIdKey: Map[String, FileDetail]): IO[ErrorFileData] = {
    val s3Files = S3Files(S3Utils(s3Async(s3Endpoint)))
    val validationProgram = for {
      _ <- s3Files.downloadFile(bucket, validationParameters)
      _ <- validUTF8(validationParameters)
      csvData <- loadCSV(validationParameters)
      _ <- validateDuplicateHeaders(validationParameters)
      _ <- validateRequired(csvData, validationParameters)
      _ <- validateAdditionalHeaders(validationParameters)
      _ <- validateRows(validationParameters, csvData, filesWithUniqueAssetIdKey, validationParameters.checkAgainstUploadedRecords)
    } yield ErrorFileData(validationParameters, FileError.None, List.empty[ValidationErrors])

    validationProgram.handleError({
      case validationExecutionError: ValidationExecutionError => validationExecutionError.errorFileData
      case err =>
        logger.error(s"Error doing validation for consignment:${validationParameters.consignmentId.toString} :${err.getMessage}")
        val singleError = Error("Validation", validationParameters.consignmentId.toString, "UNKNOWN", err.getMessage)
        val validationErrors = ValidationErrors(validationParameters.consignmentId.toString, Set(singleError))
        ErrorFileData(validationParameters, FileError.UNKNOWN, List(validationErrors))
    })
  }

  private def validUTF8(validationParameters: ValidationParameters): IO[Unit] = {
    val filePath = getFilePath(validationParameters)
    val utf8Validator = new Utf8Validator(new UTF8ValidationHandler()(logger))

    def utf8FileErrorData: ErrorFileData = {
      val messageKey = "FILE_CHECK.UTF.INVALID"
      val message = messageProperties.getProperty(messageKey, messageKey)
      val singleError = Error("FILE_CHECK", validationParameters.consignmentId.toString, "UTF8", message)
      val validationErrors = ValidationErrors(validationParameters.consignmentId.toString, Set(singleError))
      ErrorFileData(validationParameters, FileError.UTF_8, List(validationErrors))
    }

    def validateUTF8(inputStream: FileInputStream): IO[Unit] = {
      Try(utf8Validator.validate(inputStream)) match {
        case Failure(_) => IO.raiseError(ValidationExecutionError(utf8FileErrorData, List.empty[FileRow]))
        case _          => IO.unit
      }
    }

    for {
      inputStream <- IO(new FileInputStream(filePath))
      _ <- validateUTF8(inputStream)
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

  private def validateRequired(csvData: List[FileRow], validationParameters: ValidationParameters): IO[Unit] = {
    validationParameters.requiredSchema match {
      case None => IO.unit
      case Some(schema) =>
        val validationErrors = schemaValidate(Set(schema), List(csvData.head), validationParameters)
        if (validationErrors.nonEmpty) {
          IO.raiseError(ValidationExecutionError(ErrorFileData(validationParameters, FileError.SCHEMA_REQUIRED, validationErrors), csvData))
        } else {
          IO.unit
        }
    }
  }

  private def validateDuplicateHeaders(validationParameters: ValidationParameters): IO[Unit] = {
    def duplicateError(header: String): Error = {
      val errorKey = metadataConfiguration.inputToPropertyMapper(validationParameters.clientAlternateKey)(header)
      val duplicateFileError = FileError.DUPLICATE_HEADER.toString
      Error(duplicateFileError, header, "duplicate", s"$duplicateFileError.$errorKey.duplicate")
    }

    val filePath = getFilePath(validationParameters)
    val headers = CSVHandler.loadHeaders(filePath).getOrElse(Nil)
    if (headers.size > headers.toSet.size) {
      val duplicateHeaders = headers.groupBy(identity).collect {
        case (identifier, values) if values.size > 1 => identifier
      }
      val validationErrors = ValidationErrors(validationParameters.consignmentId.toString, duplicateHeaders.map(duplicateError).toSet)
      IO.raiseError(ValidationExecutionError(ErrorFileData(validationParameters, FileError.DUPLICATE_HEADER, List(validationErrors)), List.empty[FileRow]))
    } else { IO.unit }
  }

  private def validateAdditionalHeaders(validationParameters: ValidationParameters): IO[Unit] = {
    def additionalError(header: String): Error = Error(FileError.ADDITIONAL_HEADER.toString, header, "additional", "")

    def isExpectedHeader(header: String): Boolean = {
      val propertyKey = metadataConfiguration.inputToPropertyMapper(validationParameters.clientAlternateKey)(header)
      metadataConfiguration.propertyToOutputMapper(validationParameters.expectedPropertyField)(propertyKey) == "true"
    }

    val filePath = getFilePath(validationParameters)
    val headers = CSVHandler.loadHeaders(filePath).getOrElse(Nil)

    val additionalHeaders = headers.filterNot(header => isExpectedHeader(header))

    if (additionalHeaders.nonEmpty) {
      val validationErrors = ValidationErrors(validationParameters.consignmentId.toString, additionalHeaders.map(additionalError).toSet)
      IO.raiseError(ValidationExecutionError(ErrorFileData(validationParameters, FileError.ADDITIONAL_HEADER, List(validationErrors)), List.empty[FileRow]))
    } else { IO.unit }
  }

  private def validateRows(
      validationParameters: ValidationParameters,
      csvData: List[FileRow],
      filesWithUniqueAssetIdKey: Map[String, FileDetail],
      checkAgainstUploadedRecords: Boolean
  ): IO[ErrorFileData] = {
    def skipUnless(toggle: Boolean): List[ValidationErrors] => IO[List[ValidationErrors]] = validated => if (toggle) IO(validated) else IO(List.empty)
    val uniqueAssetIdKeys = filesWithUniqueAssetIdKey.keySet
    val clientAssetIdKey = metadataConfiguration.propertyToOutputMapper(validationParameters.clientAlternateKey)(validationParameters.uniqueAssetIdKey)

    for {
      missingRowErrors <- IO(RowValidator.validateMissingRows(uniqueAssetIdKeys, csvData, messageProperties, clientAssetIdKey))
      duplicateRowErrors <- skipUnless(checkAgainstUploadedRecords)(RowValidator.validateDuplicateRows(csvData, messageProperties, clientAssetIdKey))
      unknownRowErrors <- skipUnless(checkAgainstUploadedRecords)(RowValidator.validateUnknownRows(uniqueAssetIdKeys, csvData, messageProperties, clientAssetIdKey))
      protectedFieldErrors <- skipUnless(checkAgainstUploadedRecords)(validateProtectedFields(csvData, filesWithUniqueAssetIdKey, messageProperties, validationParameters))
      rowSchemaErrors <- IO(schemaValidate(validationParameters.schemaToValidate, csvData, validationParameters))
      foiCodePeriodMismatches <- IO(foiCodesPeriodsConsistent(csvData, messageProperties, validationParameters))
      combinedErrors = duplicateRowErrors |+| missingRowErrors |+| unknownRowErrors |+| protectedFieldErrors |+| rowSchemaErrors |+| foiCodePeriodMismatches
      result <-
        if (combinedErrors.nonEmpty)
          IO.raiseError(ValidationExecutionError(ErrorFileData(validationParameters, FileError.SCHEMA_VALIDATION, combinedErrors), csvData))
        else IO.pure(ErrorFileData(validationParameters))
    } yield result
  }

  private def validateProtectedFields(
      csvData: List[FileRow],
      filesWithUniqueAssetIdKey: Map[String, FileDetail],
      messageProperties: Properties,
      validationParameters: ValidationParameters
  ): List[ValidationErrors] = {

    val protectedMetadataFields = metadataConfiguration.getPropertiesByPropertyType("System").filterNot(_ == validationParameters.uniqueAssetIdKey)
    val headers = CSVHandler.loadHeaders(getFilePath(validationParameters)).getOrElse(Nil)
    for {
      metadataField <- protectedMetadataFields
      name = metadataConfiguration.propertyToOutputMapper(validationParameters.clientAlternateKey)(metadataField) if headers.contains(name)
      row <- csvData
      value = row.metadata.find(_.name == name).map(_.value).getOrElse("")
      if filesWithUniqueAssetIdKey.contains(row.matchIdentifier) && value != filesWithUniqueAssetIdKey(row.matchIdentifier).getValue(metadataField)
    } yield {
      val error = Error(
        validationProcess = PROTECTED_FIELD.toString,
        property = name,
        errorKey = PROTECTED_FIELD.toString,
        message = messageProperties.getProperty(s"$PROTECTED_FIELD.$metadataField", s"$PROTECTED_FIELD.$metadataField")
      )
      ValidationErrors(row.matchIdentifier, Set(error), List(Metadata(name, value)))
    }
  }

  private def schemaValidate(schema: Set[JsonSchemaDefinition], csvData: List[FileRow], validationParameters: ValidationParameters): List[ValidationErrors] = {
    val matchIdentifier = metadataConfiguration.propertyToOutputMapper(validationParameters.clientAlternateKey)(validationParameters.uniqueAssetIdKey)
    MetadataValidationJsonSchema
      .validate(schema, csvData)
      .collect {
        case result if result._2.nonEmpty =>
          val errors = result._2.map(error => {
            val errorKey = s"${error.validationProcess}.${error.property}.${error.errorKey}"
            Error(
              error.validationProcess.toString,
              metadataConfiguration.propertyToOutputMapper(validationParameters.clientAlternateKey)(error.property) match {
                case ""           => error.property
                case alternateKey => alternateKey
              },
              error.errorKey,
              messageProperties.getProperty(errorKey, errorKey)
            )
          })
          val errorProperties = errors.map(_.property) :+ matchIdentifier
          val data = csvData.find(_.matchIdentifier == result._1).get.metadata.filter(p => errorProperties.contains(p.name))
          ValidationErrors(result._1, errors.toSet, data)
      }
      .toList
  }

  private def getMessageProperties: Properties = {
    val source = Source.fromURL(getClass.getResource("/validation-messages/validation-messages.properties"))
    val properties = new Properties()
    properties.load(source.bufferedReader())
    properties
  }

  private def loadCSV(validationParameters: ValidationParameters): IO[List[FileRow]] = {

    def invalidCSVFileErrorData = {
      val messageKey = "FILE_CHECK.CSV.INVALID"
      val message = messageProperties.getProperty(messageKey, messageKey)
      val singleError = Error("FILE_CHECK", validationParameters.consignmentId.toString, "LOAD", message)
      val validationErrors = ValidationErrors(validationParameters.consignmentId.toString, Set(singleError))
      ErrorFileData(validationParameters, FileError.INVALID_CSV, List(validationErrors))
    }

    val filePath = getFilePath(validationParameters)
    IO(
      CSVHandler.loadCSV(
        filePath,
        validationParameters.clientAlternateKey,
        validationParameters.clientAlternateKey,
        validationParameters.uniqueAssetIdKey
      )
    ).handleErrorWith(err => {
      logger.error(s"Metadata Validation failed to load csv :${err.getMessage}")
      IO.raiseError(ValidationExecutionError(invalidCSVFileErrorData, List.empty[FileRow]))
    })
  }

  private def updateStatus(errorFileData: ErrorFileData, draftMetadata: ValidationParameters): IO[Option[Int]] = {
    val clientSecret = getClientSecret(clientSecretPath, endpoint)
    val status = if (errorFileData.validationErrors.isEmpty) "Completed" else "CompletedWithIssues"
    graphQlApi.updateConsignmentStatus(draftMetadata.consignmentId, clientSecret, "DraftMetadata", status)
  }

  private def updateConsignmentMetadataSchemaLibraryVersion(parameters: ValidationParameters): IO[Option[Int]] = {
    val clientSecret = getClientSecret(clientSecretPath, endpoint)
    val metadataSchemaLibraryVersion =
      DependencyVersionReader.findDependencyVersion.getOrElse("Failed to get schema library version")
    graphQlApi.updateConsignmentMetadataSchemaLibraryVersion(
      parameters.consignmentId,
      clientSecret,
      metadataSchemaLibraryVersion
    )
  }

  private def persistMetadata(draftMetadata: ValidationParameters, filesWithUniqueAssetIdKey: Map[String, FileDetail]): IO[List[AddOrUpdateBulkFileMetadata]] = {
    val clientSecret = getClientSecret(clientSecretPath, endpoint)
    for {
      customMetadata <- graphQlApi.getCustomMetadata(draftMetadata.consignmentId, clientSecret)
      fileData <- IO(CSVHandler.loadCSV(getFilePath(draftMetadata), draftMetadata.clientAlternateKey, draftMetadata.persistenceAlternateKey, draftMetadata.uniqueAssetIdKey))
      addOrUpdateBulkFileMetadata = MetadataUtils.filterProtectedFields(customMetadata, fileData, filesWithUniqueAssetIdKey)
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

}

object Lambda {

  case class ValidationExecutionError(errorFileData: ErrorFileData, csvData: List[FileRow]) extends Throwable
  case class ValidationParameters(
      consignmentId: UUID,
      schemaToValidate: Set[JsonSchemaDefinition],
      uniqueAssetIdKey: String,
      clientAlternateKey: String,
      persistenceAlternateKey: String,
      expectedPropertyField: String,
      requiredSchema: Option[JsonSchemaDefinition] = None,
      checkAgainstUploadedRecords: Boolean = true
  )
  def getFilePath(draftMetadata: ValidationParameters) = s"""$rootDirectory/${draftMetadata.consignmentId}/$fileName"""
  def getErrorFilePath(draftMetadata: ValidationParameters) = s"""$rootDirectory/${draftMetadata.consignmentId}/$errorFileName"""
  def getFolderPath(draftMetadata: ValidationParameters) = s"""$rootDirectory/${draftMetadata.consignmentId}"""
}
