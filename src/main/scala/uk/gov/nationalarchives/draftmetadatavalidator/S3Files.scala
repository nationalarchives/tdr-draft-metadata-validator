package uk.gov.nationalarchives.draftmetadatavalidator

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import org.typelevel.log4cats.SelfAwareStructuredLogger
import uk.gov.nationalarchives.aws.utils.s3.S3Utils
import uk.gov.nationalarchives.draftmetadatavalidator.Lambda.DraftMetadata

import java.io.File
import java.nio.file.Paths
import scala.reflect.io.Directory

class S3Files(s3Utils: S3Utils)(implicit val logger: SelfAwareStructuredLogger[IO]) {

  def downloadFile(bucket: String, draftMetadata: DraftMetadata): IO[Any] = {

    cleanup(draftMetadata.folderPath)
    val filePath = draftMetadata.filePath
    if (new File(filePath).exists()) {
      logger.info("")
      IO.unit
    } else {
      IO(new File(filePath.split("/").dropRight(1).mkString("/")).mkdirs()).flatMap(_ => {
        s3Utils.downloadFiles(bucket, draftMetadata.bucketKey, Paths.get(filePath).some)
      })
    }
  }

  def uploadFile(bucket: String, draftMetadata: DraftMetadata): IO[Unit] = for {
    _ <- s3Utils.upload(bucket, draftMetadata.bucketKey, Paths.get(draftMetadata.filePath))
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
