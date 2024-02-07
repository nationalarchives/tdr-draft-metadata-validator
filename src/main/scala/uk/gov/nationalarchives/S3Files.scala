package uk.gov.nationalarchives

import cats.effect.IO
import org.typelevel.log4cats.SelfAwareStructuredLogger
import uk.gov.nationalarchives.aws.utils.s3.S3Utils

import java.nio.file.Paths
import scala.language.postfixOps

class S3Files(s3Utils: S3Utils)(implicit val logger: SelfAwareStructuredLogger[IO]) {

  def downloadFiles(fileName: String, bucket: String): IO[Unit] = {
    for {
      _ <- s3Utils.downloadFiles(bucket, fileName, Some(Paths.get(s"/tmp/$fileName")))
      _ <- logger.info(s"Files downloaded from S3: $fileName")
    } yield ()
  }

  def uploadFiles(bucket: String, fileName: String, sourceLocation: String): IO[Unit] = for {
    _ <- s3Utils.upload(bucket, fileName, Paths.get(sourceLocation + fileName))
    _ <- logger.info(s"Files uploaded to S3: $fileName")
  } yield ()
}

object S3Files {
  def apply(s3Utils: S3Utils)(implicit logger: SelfAwareStructuredLogger[IO]): S3Files = new S3Files(s3Utils)(logger)
}
