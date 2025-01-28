package uk.gov.nationalarchives.draftmetadatavalidator.utils

import java.util.jar.JarFile
import java.io.File
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
    val classLoader = getClass.getClassLoader
    val resources = classLoader.getResources(containingFilePath)
    if (resources.hasMoreElements) {
      val url = resources.nextElement()
      val path = url.getPath
      val jarPath = path.substring(5, path.indexOf("!"))
      Some(jarPath)
    } else {
      None
    }
  }

  def findDependencyVersion(containingFilePath: String): Option[String] = {
    findJarFilePath(containingFilePath).flatMap(getDependencyVersion)
  }

}
