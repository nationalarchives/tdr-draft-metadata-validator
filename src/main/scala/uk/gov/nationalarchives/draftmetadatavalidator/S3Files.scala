package uk.gov.nationalarchives.draftmetadatavalidator

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import org.typelevel.log4cats.SelfAwareStructuredLogger
import uk.gov.nationalarchives.aws.utils.s3.S3Utils
import uk.gov.nationalarchives.draftmetadatavalidator.ApplicationConfig.fileName
import uk.gov.nationalarchives.draftmetadatavalidator.Lambda.{ValidationParameters, getFilePath, getFolderPath}

import java.io.File
import java.nio.file.Paths
import scala.reflect.io.Directory

class S3Files(s3Utils: S3Utils)(implicit val logger: SelfAwareStructuredLogger[IO]) {

  def key(validationParameters: ValidationParameters) = s"${validationParameters.consignmentId}/$fileName"

  def downloadFile(bucket: String, validationParameters: ValidationParameters): IO[Any] = {

    cleanup(getFolderPath(validationParameters))
    val filePath = getFilePath(validationParameters)
    if (new File(filePath).exists()) {
      logger.info("")
      IO.unit
    } else {
      IO(new File(filePath.split("/").dropRight(1).mkString("/")).mkdirs()).flatMap(_ => {
        s3Utils.downloadFiles(bucket, key(validationParameters), Paths.get(filePath).some)
      })
    }
  }

  def uploadFile(bucket: String, key: String, filePath: String): IO[Unit] = for {
    _ <- s3Utils.upload(bucket, key, Paths.get(filePath))
  } yield ()

  private def cleanup(path: String): Unit = {
    val directory = new Directory(new File(path))
    if (directory.exists) {
      directory.deleteRecursively()
    }
  }
}

object S3Files {
  def apply(s3Utils: S3Utils)(implicit logger: SelfAwareStructuredLogger[IO]): S3Files = new S3Files(s3Utils)(logger)
}
