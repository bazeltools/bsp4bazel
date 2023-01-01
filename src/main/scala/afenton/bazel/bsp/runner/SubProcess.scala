package afenton.bazel.bsp.runner

import cats.effect.IO
import fs2.Stream

import java.nio.file.Files
import java.nio.file.Path
import scala.concurrent.duration.FiniteDuration
import cats.effect.kernel.Resource

sealed trait SetEnv
object SetEnv {
  case object Inherit extends SetEnv
  case object Clear extends SetEnv
  case class SetValue(v: String) extends SetEnv
}

trait ExecutionResult {
  def exitCode: Int
  def workingDirectory: Path
  def stdoutLines: IO[Seq[String]]
  def stderrLines: IO[Seq[String]]
  def command: String
  def commandArgs: List[String]

  def debugString: IO[String] =
    for {
      stdoutLines <- stdoutLines
      stderrLines <- stderrLines
    } yield s"""Exited with: ${exitCode}
         |workingDir:
         |${workingDirectory}
         |
         |command:
         |${command} ${commandArgs.mkString(" ")}
         |
         |stderr:
         |${stderrLines.mkString("\n")}
         |
         |stdout:
         |${stdoutLines.mkString("\n")}
         |""".stripMargin
}

case class SubProcess(
    workingDirectory: Path,
    command: String,
    args: List[String],
    env: Map[String, SetEnv],
    clearEnvFirst: Boolean
):

  def withArgs(args: List[String]): SubProcess =
    copy(args = args)

  private def mkEnvironment: IO[ProcessBuilder] =
    IO.blocking {
      val pb = new ProcessBuilder(command)
      pb.directory(workingDirectory.toFile)
      pb.command((command :: args.toList): _*)

      val pbEnv = pb.environment()

      if clearEnvFirst then pbEnv.clear()
      pbEnv.put("PWD", workingDirectory.toString)
      pbEnv.put("CWD", workingDirectory.toString)

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

      pb
    }

  def redirectToTmp(pb: ProcessBuilder): IO[(Path, Path)] =
    IO.blocking {

      val stdout = {
        val stdout: Path = Files.createTempFile("stdout", ".log")
        stdout.toFile.deleteOnExit()
        stdout
      }

      val stderr = {
        val stderr: Path = Files.createTempFile("stderr", ".log")
        stderr.toFile.deleteOnExit()
        stderr
      }

      pb
        .redirectError(stderr.toFile)
        .redirectOutput(stdout.toFile)

      (stdout, stderr)

    }

  def start: Resource[IO, RunningProcess] =
    Resource.make {
      for
        pb <- mkEnvironment
        process = pb.start()
      yield RunningProcess(process)
    }(_.exit)

  def runUntilExit: IO[ExecutionResult] =
    for
      pb <- mkEnvironment
      files <- redirectToTmp(pb)
      (stdout, stderr) = files
      exitCode <- IO.interruptibleMany {
        val process: Process = pb.start()
        process.waitFor()
      }
    yield SubProcess.FileExecutionResult(
      workingDirectory = workingDirectory,
      exitCode = exitCode,
      stdoutPath = stdout,
      stderrPath = stderr,
      command = command,
      commandArgs = args.toList
    )

object SubProcess:

  private case class FileExecutionResult(
      workingDirectory: Path,
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

  def from(
      workingPath: Path,
      command: String,
      args: String*
  ): SubProcess =
    SubProcess(workingPath, command, args.toList, Map.empty, false)

case class RunningProcess(process: Process):

  def in(stdin: Stream[IO, String]): IO[Unit] =
    val fos = IO.blocking(process.getOutputStream)

    stdin
      .flatMap(s => Stream.fromIterator[IO](s.getBytes.iterator, 1_000))
      .through(fs2.io.writeOutputStream[IO](fos, false))
      .compile
      .drain

  def out: Stream[IO, String] =
    val is = IO.blocking(process.getInputStream)
    fs2.io
      .readInputStream[IO](is, 1_000, false)
      .through(fs2.text.utf8.decode)

  def err: Stream[IO, String] =
    val es = IO.blocking(process.getErrorStream)
    fs2.io
      .readInputStream[IO](es, 1_000, false)
      .through(fs2.text.utf8.decode)

  def exit: IO[Unit] =
    IO.interruptibleMany(process.destroy())
