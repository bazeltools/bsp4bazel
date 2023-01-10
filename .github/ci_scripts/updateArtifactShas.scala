//> using file "project.scala"

import scala.util.matching.Regex

private def join(l1: String, l2: String): String =
  if l1.isEmpty then l2
  else s"$l1\n$l2"

// Substitutes bazel_rule SHA into README
def substituteReadme(readmeContent: String, newSha: String): String =
  performSed(readmeContent, raw"sha256 =".r, raw"\"[a-f0-9]{64}\"".r, s"\"$newSha\"")

// Substitutes new SHA defintions into Python
def substituteBazelRule(
    ruleContent: String,
    newDefinitions: String
): String =

  val placeholder: String = "<<-- PLACEHOLDER -->>"
  val startExpr = raw"_build_artifact_shas = {".r
  val endExpr = raw"\}".r

  require(
    !startExpr.findAllIn(ruleContent).toList.isEmpty,
    s"File doesn't contain expected expression: $startExpr"
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

private def binEntries(map: Map[String, String]): Map[String, String] =
  map.filterKeys(_.contains("bazel-bsp")).toMap

/** Update the given inputFile with the SHA256 of the artifacts in artifactDir.
  * And print to stdout.
  *
  * @param bazelRuleFile
  *   The bazel_rule file to update
  * @param readmeFile
  *   The README file to update
  * @param artifactDir
  *   The directory where artifacts are stored
  */
@main def updateArtifactShas(
    bazelRuleFile: String,
    readmeFile: String,
    artifactDir: String
): Unit =

  val bazelRulePath = os.Path(bazelRuleFile, os.pwd)
  val readmePath = os.Path(readmeFile, os.pwd)
  val artifactPath = os.Path(artifactDir, os.pwd)

  require(os.isDir(artifactPath), s"${artifactPath} is not a directory")
  List(bazelRulePath, readmePath).foreach { f =>
    require(os.exists(f), s"$f file wasn't found")
  }

  val artifactShas: Map[String, String] = os
    .list(artifactPath)
    .filter(_.ext == "sha256")
    .map(a => (a.last, os.read(a)))
    .toMap

  val newRule = substituteBazelRule(
    os.read(bazelRulePath),
    generatePythonMap(binEntries(artifactShas))
  )

  println(s"Writing new $bazelRulePath")
  os.write.over(bazelRulePath, newRule)

  val newReadme = substituteBazelRule(
    os.read(readmePath),
    artifactShas("bazel_rules.tar.gz.sha256")
  )
  println(s"Writing new $readmePath")
  os.write.over(readmePath, newReadme)