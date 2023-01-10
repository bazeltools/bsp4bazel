//> using file "project.scala"

import scala.util.matching.Regex

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
  val newContent = performSed(
    content,
    linePattern.r,
    replacementPattern.r,
    replacement
  )

  if inplace then
    println(s"Writing to $inputPath")
    os.write.over(inputPath, newContent)
  else println(newContent)
