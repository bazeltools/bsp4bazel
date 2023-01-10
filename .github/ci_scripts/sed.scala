//> using scala "3.2"
//> using lib "com.lihaoyi::os-lib:0.9.0"

import scala.util.matching.Regex

def sed(
    input: String,
    linePattern: Regex,
    replacePattern: Regex,
    replacement: String
): String =
  input
    .split("\n")
    .map { line =>
      if linePattern.findFirstIn(line).isDefined then
        replacePattern.replaceAllIn(line, replacement)
      else line
    }
    .mkString("\n")

/** A simple sed implementation, that works cross-platform (subtle differences
  * between mac/linux were causing problems)
  *
  * @param inplace
  *   If true, overwrite the input file, otherwise write to stdout
  * @param linePattern
  *   Only apply to lines that match this regex
  * @param replacementPattern
  *   Find this regex and replace it with the replacement
  * @param replacement
  *   Text to replace with.
  * @param inputFile
  *   The file to read
  */
@main def sed(
    inplace: Boolean,
    linePattern: String,
    replacementPattern: String,
    replacement: String,
    inputFile: String
): Unit =

  val inputPath = os.Path(inputFile, os.pwd)

  val content = os.read(inputPath)
  val newContent = sed(
    content,
    linePattern.r,
    replacementPattern.r,
    replacement
  )

  if inplace then
    println(s"Writing to $inputPath")
    os.write.over(inputPath, newContent)
  else println(newContent)
