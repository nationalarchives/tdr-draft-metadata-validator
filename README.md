# tdr-draft-metadata-validator

The tdr-draft-metadata-validator is a Lambda that is invoked with a consignment ID from the tdrMetadataChecks step function

![drMetadataChecks ](images/metadata-validation-stepfunction-gif.gif)


```
// validates, saves metadata, updates consignment status - the response value is not used by the step function
def handleRequest(input: java.util.Map[String, Object], context: Context): APIGatewayProxyResponseEvent = {
    val consignmentId = extractConsignmentId(input)
    val schemaToValidate: Set[JsonSchemaDefinition] = Set(BASE_SCHEMA, CLOSURE_SCHEMA)
    val s3Files = S3Files(S3Utils(s3Async(s3Endpoint)))
    val draftMetadata = DraftMetadata(UUID.fromString(consignmentId))
    val unexpectedFailureResponse = new APIGatewayProxyResponseEvent()
    unexpectedFailureResponse.setStatusCode(500)

    val requestHandler: IO[APIGatewayProxyResponseEvent] = for {
      errorFileData <- doValidation(draftMetadata,schemaToValidate)  
      errorFilePath <- IO(writeErrorFileDataToFile(draftMetadata, Right(errorFileData)))  // save the error file locally
      _ <- s3Files.uploadFile(bucket, s"${draftMetadata.consignmentId}/$errorFileName", errorFilePath) // upload error file to s3
      _ <- if(errorFileData.validationErrors.isEmpty) persistMetadata(draftMetadata) // if no errors persist metadata to DB
      statusCode <- updateStatus(errorFileData, draftMetadata) // update the consignment status 
    } yield {  
      val response = new APIGatewayProxyResponseEvent()
      response.setStatusCode(statusCode)
      response
    }
    // let's stop blowing up on unexpected errors but do log
    requestHandler.handleErrorWith(_ => IO(unexpectedFailureResponse)).unsafeRunSync()(cats.effect.unsafe.implicits.global)
  }

  // The validation involves several processes. When one fails furthur validation processes will not be tried
  // Probably best way to handle this is to raise the error and handle error at end to return the error so furthur processing can take plac
  // writing error file, updating status etc
  private def doValidation(draftMetadata: DraftMetadata, schemaToValidate: Set[JsonSchemaDefinition]):IO[ErrorFileData] = {
    ( for {
      _ <- s3Files.downloadFile(bucket, draftMetadata)
      _ <-  validUTF(draftMetadata)
      _ <-  validCSV(draftMetadata)
      csvData <- loadCSVData(draftMetadata: DraftMetadata)
      // do other validations using same pattern  
      _ <-  validateRequired(csvData,draftMetadata.consignmentId.toString)
      errorFile  <- validateMetadata(draftMetadata,csvData,schemaToValidate)
    } yield errorFile).handleErrorWith {
      case validationError:ValidationError => IO.pure(validationError.errorData)
      case _:Throwable =>  IO.pure(ErrorFileData(draftMetadata)) // with useful error data
    }
  }

  // for validation
  // validate required columns using a schema
  private def validateRequired(csvData: List[FileRow], consignmentID: String):IO[Unit] = ??? // IO.raiseError(new ValidationError(ErrorFileData with requiredErrors)
  private def validUTF(draftMetadata: DraftMetadata): IO[Unit] = ??? // IO.raiseError(new ValidationError(ErrorFileData with validUTF error)
  private def validCSV(draftMetadata: DraftMetadata):IO[Unit] = ???  // IO.raiseError(new ValidationError(ErrorFileData with validCSV error)
  private def loadCSVData(draftMetadata: DraftMetadata) :IO[List[FileRow]] = ??? // IO.raiseError(new ValidationError(ErrorFileData with validCSV error)
  // validate using schema
  private def validateMetadata(draftMetadata: DraftMetadata, csvData: List[FileRow], schema: Set[JsonSchemaDefinition]): IO[ErrorFileData] = ???  // not raising error here

  case class ValidationError(errorData:ErrorFileData ) extends Throwable
  private def updateStatus(errorFileData: ErrorFileData, draftMetadata: DraftMetadata):IO[Int] = ???```
