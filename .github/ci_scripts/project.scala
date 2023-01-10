//> using scala "3.2"
//> using lib "com.lihaoyi::os-lib:0.9.0"

import scala.util.matching.Regex
import scala.math.Ordering
import scala.util.Try
import scala.collection.mutable.ListBuffer

case class SemVer(major: Int, minor: Int, patch: Int):
  def asString: String = s"$major.$minor.$patch"

object SemVer:

  given Ordering[SemVer] = new Ordering {
    def compare(a: SemVer, b: SemVer): Int =
      if a.major != b.major then a.major - b.major
      else if a.minor != b.minor then a.minor - b.minor
      else a.patch - b.patch
  }

  val Pattern = raw"^(\d+)\.(\d+).(\d+)$$".r

  def fromString(str: String): Option[SemVer] =
    str match
      case Pattern(major, minor, patch) =>
        Try(SemVer(major.toInt, minor.toInt, patch.toInt)).toOption

      case _ => None

def sed(
    input: String,
    replacePattern: Regex,
    replacement: String
): String = sed(input, replacePattern, replacePattern, replacement)

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

trait Console:
  def print(s: String): Unit
  def println(s: String): Unit
  def error(s: String): Unit
  def errorln(s: String): Unit

  def outLines: List[String] 
  def errLines: List[String] 

  def printOuts: Unit = System.out.print(outLines.mkString)
  def printErrs: Unit = System.err.print(errLines.mkString)

object Console:
  def default: Console = new ConsoleImpl

  private class ConsoleImpl extends Console:
    private val outBuffer: ListBuffer[String] = ListBuffer.empty
    private val errBuffer: ListBuffer[String] = ListBuffer.empty

    def print(str: String): Unit = outBuffer.append(str)
    def println(str: String): Unit = outBuffer.append(s"$str\n")
    def error(str: String): Unit = errBuffer.append(str)
    def errorln(str: String): Unit = errBuffer.append(s"$str\n")

    def outLines: List[String] = outBuffer.toList
    def errLines: List[String] = errBuffer.toList

def runWith[A](fn: (os.Path, Console) => Unit): Unit =
  val console = Console.default 
  fn(os.pwd, console)
  console.printOuts 
  console.printErrs