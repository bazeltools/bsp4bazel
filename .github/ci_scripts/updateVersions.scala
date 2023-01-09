//> using file "project.scala"

import math.Ordering.Implicits.infixOrderingOps

def updateFile(
    file: String,
    cwd: os.Path,
    linePattern: String,
    currentVersionStr: String,
    newVersionStr: String
): Unit =
  val filePath = os.Path(file, cwd)
  require(os.exists(filePath), s"$filePath file wasn't found")

  val (currentVersion :: newVersion :: Nil) =
    List(currentVersionStr, newVersionStr).map { verStr =>
      SemVer.fromString(verStr) match
        case Some(semVer) => semVer
        case None =>
          throw new Exception(s"Invalid version string: $verStr")
    }

  require(newVersion > currentVersion, s"New version isn't greater than current version: n:$newVersion, c:$currentVersion")

  val newContent =
    sed(
      os.read(filePath),
      linePattern.r,
      raw"(\d+)\.(\d+).(\d+)".r,
      newVersion.asString
    )

  println(s"Updating $filePath")
  os.write.over(filePath, newContent)

def updateVersions(
    currentVersion: String,
    newVersion: String,
    cwd: os.Path,
    console: Console
): Unit =
  updateFile(
    "build.sbt",
    cwd,
    "^val bazelBspVersion",
    currentVersion,
    newVersion
  )
  updateFile(
    "bazel_rules/bazel_bsp_setup.bzl",
    cwd,
    "^_bazel_bsp_version",
    currentVersion,
    newVersion
  )
  updateFile("README.md", cwd, currentVersion, currentVersion, newVersion)

/** Update the version number across files with it in
  *
  * @param currentVersion
  *   The current version number (used for pattern matching occurences)
  * @param newVersion
  *   New version number
  */
@main def updateVersions(
    currentVersion: String,
    newVersion: String
): Unit =
  runWith(updateVersions(currentVersion, newVersion, _, _))
