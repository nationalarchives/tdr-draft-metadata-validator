package uk.gov.nationalarchives.draftmetadatavalidator

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import org.typelevel.log4cats.SelfAwareStructuredLogger
import uk.gov.nationalarchives.aws.utils.s3.S3Utils
import uk.gov.nationalarchives.draftmetadatavalidator.Lambda.{DraftMetadata, getFilePath}

import java.io.File
import java.nio.file.Paths

class S3Files(s3Utils: S3Utils)(implicit val logger: SelfAwareStructuredLogger[IO]) {

  def key(draftMetadata: DraftMetadata) = s"${draftMetadata.consignmentId}/${draftMetadata.fileName}"

  def downloadFile(bucket: String, draftMetadata: DraftMetadata): IO[Any] = {
    val filePath = getFilePath(draftMetadata)
    if (new File(filePath).exists()) {
      IO.unit
    } else {
      IO(new File(filePath.split("/").dropRight(1).mkString("/")).mkdirs()).flatMap(_ => {
        s3Utils.downloadFiles(bucket, key(draftMetadata), Paths.get(filePath).some)
      })
    }
  }

  def uploadFiles(bucket: String, draftMetadata: DraftMetadata): IO[Unit] = for {
    _ <- s3Utils.upload(bucket, key(draftMetadata), Paths.get(getFilePath(draftMetadata)))
  } yield ()
}

object S3Files {
  def apply(s3Utils: S3Utils)(implicit logger: SelfAwareStructuredLogger[IO]): S3Files = new S3Files(s3Utils)(logger)
}
