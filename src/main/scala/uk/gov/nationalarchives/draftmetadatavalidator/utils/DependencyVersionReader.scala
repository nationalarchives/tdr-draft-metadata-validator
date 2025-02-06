package uk.gov.nationalarchives.draftmetadatavalidator.utils

import java.io.File
import java.util.jar.JarFile
import scala.util.Try

object DependencyVersionReader {
  private def getDependencyVersion(jarFilePath: String): Option[String] = {
    Try {
      val jarFile = new JarFile(new File(jarFilePath))
      val manifest = jarFile.getManifest
      val version = manifest.getMainAttributes.getValue("Implementation-Version")
      jarFile.close()
      version
    }.toOption
  }

  private def findJarFilePath(containingFilePath: String): Option[String] = {
    val filePath = if (!containingFilePath.startsWith("/")) containingFilePath else containingFilePath.substring(1)
    val resource = Option(getClass.getClassLoader.getResource(filePath))
    resource.map(resourceValue => resourceValue.getPath.substring(5, resourceValue.getPath.indexOf("!")))
  }

  def findDependencyVersion(containingFilePath: String): Option[String] = {
    findJarFilePath(containingFilePath).flatMap(getDependencyVersion)
  }

}
