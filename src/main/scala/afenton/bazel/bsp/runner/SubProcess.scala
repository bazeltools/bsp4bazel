package afenton.bazel.bsp.runner

import java.nio.file.{Files, Path}
import cats.effect.IO

sealed trait SetEnv
object SetEnv {
  case object Inherit extends SetEnv
  case object Clear extends SetEnv
  case class SetValue(v: String) extends SetEnv
}

trait ExecutionResult {
  def exitCode: Int
  def stdoutLines: IO[Seq[String]]
  def stderrLines: IO[Seq[String]]
  def command: String
  def commandArgs: List[String]

  def debugString: IO[String] =
    for {
      stdoutLines <- stdoutLines
      stderrLines <- stderrLines
    } yield s"""exit code: ${exitCode}
         |${stdoutLines.mkString("\n")}
         |
         |stderr:
         |${stderrLines.mkString("\n")}
         |""".stripMargin
}

object SubProcess:
  private case class FileExecutionResult(
      command: String,
      commandArgs: List[String],
      exitCode: Int,
      stdoutPath: Path,
      stderrPath: Path
  ) extends ExecutionResult {
    def stdoutLines: IO[Seq[String]] = IO(
      scala.io.Source.fromFile(stdoutPath.toFile).getLines().toStream
    )

    def stderrLines: IO[Seq[String]] = IO(
      scala.io.Source.fromFile(stderrPath.toFile).getLines().toStream
    )
  }

  def runCommand(workingPath: Path, command: String, args: String*)(
      env: Map[String, SetEnv] = Map.empty,
      overrideStdoutPath: Option[Path] = None,
      overrideStderrPath: Option[Path] = None,
      clearEnvFirst: Boolean = false
  ): IO[ExecutionResult] =
    IO {
      val pb = new ProcessBuilder(command);
      pb.directory(workingPath.toFile)
      pb.command((command :: args.toList): _*)

      val stdout = overrideStdoutPath.getOrElse {
        val stdout: Path = Files.createTempFile("stdout", ".log")
        stdout.toFile.deleteOnExit()
        stdout
      }

      val stderr = overrideStderrPath.getOrElse {
        val stderr: Path = Files.createTempFile("stderr", ".log")
        stderr.toFile.deleteOnExit()
        stderr
      }

      pb
        .redirectError(stderr.toFile)
        .redirectOutput(stdout.toFile)

      val pbEnv = pb.environment()

      if (clearEnvFirst) {
        pbEnv.clear()
      }
      pbEnv.put("PWD", workingPath.toString)
      pbEnv.put("CWD", workingPath.toString)

      env.foreach { case (k, setEnv) =>
        setEnv match {
          case SetEnv.Inherit =>
            // We set some sane CWD/PWD and similar defaults above.
            // We need to remove that incase this is missing in the outer env.
            pbEnv.remove(k)
            Option(System.getenv(k)).foreach { outerV =>
              pbEnv.put(k, outerV)
            }
          case SetEnv.Clear =>
            pbEnv.remove(k)
          case SetEnv.SetValue(v) =>
            pbEnv.put(k, v)
        }
      }

      val process = pb.start()

      val exitCode = process.waitFor()

      FileExecutionResult(
        exitCode = exitCode,
        stdoutPath = stdout,
        stderrPath = stderr,
        command = command,
        commandArgs = args.toList
      )
    }
