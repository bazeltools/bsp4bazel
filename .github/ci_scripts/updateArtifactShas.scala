//> using file "project.scala"

import scala.util.matching.Regex

def join(l1: String, l2: String): String =
  if l1.isEmpty then l2
  else s"$l1\n$l2"

def substituteReadme(readmeContent: String, newSha: String): String =
  sed(readmeContent, "sha256 =".r, s"\"[a-f0-9]{64}\"".r, s"\"$newSha\"") match
    case Some(c) => c
    case None =>
      throw new Exception(
        s"No substitutions made in README.md, with content: $readmeContent"
      )

def substituteBazelRule(
    ruleContent: String,
    newDefinitions: String
): String =

  val placeholder: String = "<<-- PLACEHOLDER -->>"
  val startExpr = raw"_build_artifact_shas = \{".r
  val endExpr = raw"\}".r

  require(
    !startExpr.findAllIn(ruleContent).toList.isEmpty,
    s"Didn't find expected expression $startExpr in:\n$ruleContent"
  )

  val stripped = ruleContent
    .split("\n")
    .foldLeft((false, "")) {
      // Start def
      case ((false, result), startExpr()) =>
        (true, join(result, placeholder))

      // Outside definition
      case ((false, result), line) =>
        (false, join(result, line))

      // End def
      case ((true, result), endExpr()) =>
        (false, result)

      // Inside definition
      case ((true, result), line) =>
        (true, result)

    }
    ._2

  stripped.replaceAllLiterally(placeholder, newDefinitions)

def generatePythonMap(artifacts: Map[String, String]): String =
  require(!artifacts.isEmpty, "No artifacts found")

  // e.g. /my/path/to/bazel-bsp-linux-x86.sha256
  val filePattern = raw"^bazel-bsp-([\w-]+).sha256$$".r

  val inner = artifacts
    .map { (filename, sha) =>
      val filePattern(arch) = filename: @unchecked
      (arch.trim, sha.trim)
    }
    .map { (arch, sha) =>
      s"    \"$arch\": \"$sha\""
    }
    .mkString(",\n")

  List(
    "_build_artifact_shas = {",
    inner,
    "}"
  ).mkString("\n")

def updateFile(
    filePath: os.Path,
    artifactPath: os.Path,
    fn: (String, Map[String, String]) => String
): Unit =
  require(os.exists(filePath), s"$filePath wasn't found")
  require(os.isDir(artifactPath), s"${artifactPath} is not a directory")

  val artifactShas: Map[String, String] = os
    .list(artifactPath)
    .filter(_.ext == "sha256")
    .map(a => (a.last, os.read(a).trim))
    .toMap

  require(!artifactShas.isEmpty, s"No artifacts SHAs found at $artifactPath")

  val newContent = fn(os.read(filePath), artifactShas)

  println(s"Writing new $filePath")
  os.write.over(filePath, newContent)

/** Update bazee_rule.tar.gz artifcat SHA in README.md.
  *
  * @param artifactDir
  *   The directory where artifacts are stored
  */
@main def updateReadme(artifactDir: String): Unit =
  val artifactPath = os.Path(artifactDir, os.pwd)

  updateFile(
    os.pwd / "README.md",
    artifactPath,
    (content, artifacts) =>
      substituteReadme(content, artifacts("bazel_rules.tar.gz.sha256"))
  )

def binEntries(map: Map[String, String]): Map[String, String] =
  map.filterKeys(_.contains("bazel-bsp")).toMap

/** Update bin artifact SHA's in bazel_rules.
  *
  * @param artifactDir
  *   The directory where artifacts are stored
  */
@main def updateBazelRule(artifactDir: String): Unit =
  val artifactPath = os.Path(artifactDir, os.pwd)

  updateFile(
    os.pwd / "bazel_rules" / "bazel_bsp_setup.bzl",
    artifactPath,
    (content, artifacts) => substituteBazelRule(content, generatePythonMap(binEntries(artifacts)))
  )

