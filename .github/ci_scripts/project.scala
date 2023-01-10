//> using scala "3.2"
//> using lib "com.lihaoyi::os-lib:0.9.0"
//> using lib "org.scalameta::munit::0.7.29"

import scala.util.matching.Regex

def performSed(
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
