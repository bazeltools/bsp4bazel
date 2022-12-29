package afenton.bazel.bsp.runner

import cats.effect.IO

import java.nio.file.Files
import java.nio.file.Path
import fs2.Stream

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

case class SubProcess(
    workingPath: Path,
    command: String,
    args: List[String],
    env: Map[String, SetEnv],
    stdoutPath: Option[Path],
    stderrPath: Option[Path],
    clearEnvFirst: Boolean
):

  private def setupEnvironment: IO[(ProcessBuilder, Path, Path)] =
    IO.blocking {
      val pb = new ProcessBuilder(command)
      pb.directory(workingPath.toFile)
      pb.command((command :: args.toList): _*)

      val stdout = stdoutPath.getOrElse {
        val stdout: Path = Files.createTempFile("stdout", ".log")
        stdout.toFile.deleteOnExit()
        stdout
      }

      val stderr = stderrPath.getOrElse {
        val stderr: Path = Files.createTempFile("stderr", ".log")
        stderr.toFile.deleteOnExit()
        stderr
      }

      pb
        .redirectError(stderr.toFile)
        .redirectOutput(stdout.toFile)

      val pbEnv = pb.environment()

      if clearEnvFirst then pbEnv.clear()
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

      (pb, stdout, stderr)
    }

  def start: IO[ProcessIO] =
    for
      env <- setupEnvironment
      (pb, out, err) = env
      process = pb.start()
    yield 
      ProcessIO(process)

  def runUntilExit: IO[ExecutionResult] =
    for
      env <- setupEnvironment
      (pb, stdout, stderr) = env
      exitCode <- IO.blocking {
        val process: Process = pb.start()
        process.waitFor()
      }
    yield SubProcess.FileExecutionResult(
      exitCode = exitCode,
      stdoutPath = stdout,
      stderrPath = stderr,
      command = command,
      commandArgs = args.toList
    )

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

  def withCommand(workingPath: Path, command: String, args: String*): SubProcess =
    SubProcess(workingPath, command, args.toList, Map.empty, None, None, false)

case class ProcessIO(process: Process):

  def in(stdin: Stream[IO, String]): IO[Unit] =
    val fos = IO(process.getOutputStream)

    stdin
      .flatMap(s => Stream.fromIterator[IO](s.getBytes.iterator, 1_000))
      .through(fs2.io.writeOutputStream[IO](fos, false))
      .compile
      .drain

  def out: Stream[IO, String] =
    val ios = IO(process.getInputStream)
    fs2.io
      .readInputStream[IO](ios, 1_000, false)
      .through(fs2.text.utf8.decode)

  def err: Stream[IO, String] =
    val eos = IO(process.getErrorStream)
    fs2.io
      .readInputStream[IO](eos, 1_000, false)
      .through(fs2.text.utf8.decode)
