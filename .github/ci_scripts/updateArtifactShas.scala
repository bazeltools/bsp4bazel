//> using scala "3.2"
//> using lib "com.lihaoyi::os-lib:0.9.0"

private def join(l1: String, l2: String): String =
  if l1.isEmpty then l2
  else s"$l1\n$l2"

// Substitutes in new SHA defintions
def substituteShaDefinitions(
    fileContent: String,
    newDefinitions: String
): String =

  val placeholder: String = "<<-- PLACEHOLDER -->>"
  val startExpr = raw"_build_artifact_shas = \{".r
  val endExpr = raw"\}".r

  require(
    !startExpr.findAllIn(fileContent).toList.isEmpty,
    s"File doesn't contain expected expression: $startExpr"
  )

  val stripped = fileContent
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

def generateShaDefinitions(artifacts: List[(os.Path, String)]): String =
  require(!artifacts.isEmpty, "No artifacts found")

  // e.g. /my/path/to/bazel-bsp-linux-x86.sha256
  val filePattern = raw"^bazel-bsp-([\w-]+).sha256$$".r

  val inner = artifacts
    .map { (artifact, sha) =>
      val filename = artifact.last
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


/** Update the given inputFile with the SHA256 of the artifacts in artifactDir.
  * And print to stdout.
  *
  * @param inputFile
  *   The file to update
  * @param artifactDir
  *   The directory where artifacts are stored
  */
@main def updateArtifactShas(inputFile: String, artifactDir: String) =

  val artifactPath = os.Path(artifactDir, os.pwd)
  val inputPath = os.Path(inputFile, os.pwd)

  require(os.isDir(artifactPath), s"${artifactPath} is not a directory")
  require(os.exists(inputPath), s"$inputPath file wans't found")

  val artifactShas = os
    .list(artifactPath)
    .filter(_.ext == "sha256")
    .map(a => (a, os.read(a)))
    .toList

  val newContent = substituteShaDefinitions(
    os.read(inputPath),
    generateShaDefinitions(artifactShas)
  )

  println(newContent)
