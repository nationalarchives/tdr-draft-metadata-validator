package uk.gov.nationalarchives.tdr.draftmetadatachecks

import cats.effect.IO
import org.typelevel.log4cats.SelfAwareStructuredLogger
import uk.gov.nationalarchives.utf8.validator.{ValidationException, ValidationHandler}

class UTF8ValidationHandler()(implicit logger: SelfAwareStructuredLogger[IO]) extends ValidationHandler {
  override def error(message: String, byteOffset: Long): Unit = {
    logger.error("[Error][@" + byteOffset + "] " + message)
    throw new ValidationException(message, byteOffset)
  }
}
