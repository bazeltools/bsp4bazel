//> using file "project.scala"

import scala.collection.mutable.Stack

def extractVersion(buildContent: String): Option[SemVer] =
  val VersionExpr = raw"^val bazelBspVersion = \"(\d+)\.(\d+).(\d+)\"$$".r

  buildContent
    .split("\n")
    .collectFirst { case VersionExpr(major, minor, patch) =>
      SemVer(major.toInt, minor.toInt, patch.toInt)
    }

def currentVersion(buildFile: String, cwd: os.Path, console: Console): Unit =

  val buildPath = os.Path(buildFile, cwd)
  require(os.exists(buildPath), s"$buildPath file wasn't found")

  val content = os.read(buildPath)
  extractVersion(content) match
    case Some(semVer) => console.println(semVer.asString) 
    case None =>
      throw new Exception(s"Didn't find version in build file: $content")

/** Return the current version number from the build file
  *
  * @param buildFile
  *   Path to build.sbt
  */
@main def currentVersion(buildFile: String): Unit =
  runWith(currentVersion(buildFile, _, _))
