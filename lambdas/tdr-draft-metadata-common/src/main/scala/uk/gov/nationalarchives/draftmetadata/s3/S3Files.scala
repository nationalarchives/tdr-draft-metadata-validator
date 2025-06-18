package uk.gov.nationalarchives.draftmetadata.s3

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import org.typelevel.log4cats.SelfAwareStructuredLogger
import uk.gov.nationalarchives.aws.utils.s3.S3Utils
import uk.gov.nationalarchives.draftmetadata.config.ApplicationConfig.{fileName, rootDirectory}

import java.io.File
import java.nio.file.Paths
import scala.reflect.io.Directory

class S3Files(s3Utils: S3Utils)(implicit val logger: SelfAwareStructuredLogger[IO]) {

  def key(consignmentId: String) = s"${consignmentId}/$fileName"

  def downloadFile(bucket: String, consignmentId: String): IO[Any] = {
    cleanup(getFolderPath(consignmentId))
    val filePath = getFilePath(consignmentId)
    if (new File(filePath).exists()) {
      IO.unit
    } else {
      IO(new File(filePath.split("/").dropRight(1).mkString("/")).mkdirs()).flatMap(_ => {
        s3Utils.downloadFiles(bucket, key(consignmentId), Paths.get(filePath).some)
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

  def getFilePath(consignmentId:String) = s"""$rootDirectory/$consignmentId/$fileName"""

  def getFolderPath(consignmentId:String) = s"""$rootDirectory/$consignmentId"""
}

object S3Files {
  def apply(s3Utils: S3Utils)(implicit logger: SelfAwareStructuredLogger[IO]): S3Files = new S3Files(s3Utils)(logger)
}
