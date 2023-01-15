//> using file "project.scala"

import math.Ordering.Implicits.infixOrderingOps

/** Update a SemVer version in a given file
  *
  * @param file
  *   The file to update
  * @param linePattern
  *   The pattern to find the line to update
  * @param newVersion
  *   The version string to replace with
  */
@main def updateVersion(
    file: String,
    linePattern: String,
    newVersionStr: String
): Unit =
  val filePath = os.Path(file, os.pwd)
  require(os.exists(filePath), s"$filePath file wasn't found")

  val newVersion = SemVer.fromString(newVersionStr) match
    case Some(semVer) => semVer
    case None =>
      throw new Exception(s"Invalid version string: $newVersionStr")

  val newContent =
    sed(
      os.read(filePath),
      linePattern.r,
      raw"(\d+)\.(\d+).(\d+)".r,
      newVersion.asString
    ) match
      case Some(value) => value
      case None => throw new Exception(s"No substitutions made in $filePath")

  println(s"Updating $filePath")
  os.write.over(filePath, newContent)
