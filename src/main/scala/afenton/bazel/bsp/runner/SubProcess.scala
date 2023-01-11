package afenton.bazel.bsp.runner

import cats.effect.IO
import cats.effect.kernel.Resource
import fs2.Stream
import fs2.io.file.{Files => fs2Files, Path => fs2Path}

import java.nio.file.{Files, Path}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.Duration

sealed trait SetEnv
object SetEnv {
  case object Inherit extends SetEnv
  case object Clear extends SetEnv
  case class SetValue(v: String) extends SetEnv
}

trait ExecutionResult {
  def exitCode: Int
  def workingDirectory: Path
  def stdoutLines: Stream[IO, String]
  def stderrLines: Stream[IO, String]
  def command: String
  def commandArgs: List[String]

  def debugString: IO[String] =
    for {
      stdoutLines <- stdoutLines.compile.toList
      stderrLines <- stderrLines.compile.toList
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

  private def redirectToTmp(pb: ProcessBuilder): Resource[IO, (Path, Path)] =
    Resource.make(IO.blocking {

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
    }) { case (out, err) =>
      IO.blocking {
        out.toFile.delete()
        err.toFile.delete()
      }
    }

  def start: Resource[IO, RunningProcess] =
    Resource
      .make {
        for
          pb <- mkEnvironment
          process = pb.start()
        yield process
      } { process =>
        IO.interruptibleMany(process.destroy())
      }
      .map(RunningProcess(_))

  def runUntilExit(duration: Duration): Resource[IO, ExecutionResult] =
    for
      pb <- Resource.eval(mkEnvironment)
      files <- redirectToTmp(pb)
      (stdout, stderr) = files
      exitCode <- Resource.eval(
        IO.interruptibleMany {
          val process: Process = pb.start()
          process.waitFor()
        }.timeout(duration)
      )
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
    def stdoutLines: Stream[IO, String] =
      fs2Files[IO]
        .readAll(fs2Path.fromNioPath(stdoutPath))
        .through(fs2.text.utf8.decode)
        .through(fs2.text.lines)

    def stderrLines: Stream[IO, String] =
      fs2Files[IO]
        .readAll(fs2Path.fromNioPath(stderrPath))
        .through(fs2.text.utf8.decode)
        .through(fs2.text.lines)
  }

  def from(
      workingPath: Path,
      command: String,
      args: String*
  ): SubProcess =
    SubProcess(workingPath, command, args.toList, Map.empty, false)

class RunningProcess(process: Process):

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
