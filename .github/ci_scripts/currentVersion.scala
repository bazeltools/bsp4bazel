//> using file "project.scala"

import scala.collection.mutable.Stack

def extractVersion(buildContent: String): Option[SemVer] =
  val VersionExpr = raw"^val bazelBspVersion = \"(\d+)\.(\d+).(\d+)\"$$".r

  buildContent
    .split("\n")
    .collectFirst { case VersionExpr(major, minor, patch) =>
      SemVer(major.toInt, minor.toInt, patch.toInt)
    }

/** Return the current version number from the build file
  *
  * @param buildFile
  *   Path to build.sbt
  */
@main def currentVersion(buildFile: String): Unit =

  val buildPath = os.Path(buildFile, os.pwd)
  require(os.exists(buildPath), s"$buildPath file wasn't found")

  val content = os.read(buildPath)
  extractVersion(content) match
    case Some(semVer) => 
      println(semVer.asString) 
    case None =>
      throw new Exception(s"Didn't find version in build file: $content")
