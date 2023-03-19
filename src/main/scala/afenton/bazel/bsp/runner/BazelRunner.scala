package afenton.bazel.bsp.runner

import afenton.bazel.bsp.FilesIO
import afenton.bazel.bsp.Logger
import afenton.bazel.bsp.protocol.BspServer
import afenton.bazel.bsp.protocol.BuildTargetIdentifier
import afenton.bazel.bsp.protocol.TextDocumentIdentifier
import afenton.bazel.bsp.protocol.UriFactory
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all._
import fs2.Stream
import fs2.io.file.Watcher
import fs2.io.file.Path as FPath
import io.bazel.rules_scala.diagnostics.diagnostics.Diagnostic
import io.bazel.rules_scala.diagnostics.diagnostics.FileDiagnostics
import io.bazel.rules_scala.diagnostics.diagnostics.TargetDiagnostics
import io.circe.Decoder
import io.circe.generic.semiauto.*
import io.circe.syntax._

import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.util.Failure
import scala.util.Success
import scala.util.Success.apply
import scala.util.Try
import afenton.bazel.bsp.protocol.BspClient
import cats.parse.SemVer

trait BazelRunner:
  def compile(target: BazelLabel): Stream[IO, FileDiagnostics]
  def clean: IO[Unit]
  def shutdown: IO[Unit]
  def bspConfig(target: BazelLabel): IO[BazelRunner.BspConfig]
  def bspTargets: IO[List[BazelLabel]]

object BazelRunner:

  enum ExitCode(val code: Int):
    case Ok extends ExitCode(0)
    case BuildFailed extends ExitCode(1)

  object ExitCode:
    def fromCode(code: Int): ExitCode = code match
      case 0 => ExitCode.Ok
      case 1 => ExitCode.BuildFailed

  enum Command(val asString: String):
    case Query extends Command("query")
    case Build extends Command("build")
    case Run extends Command("run")
    case Test extends Command("test")
    case Shutdown extends Command("shutdown")
    case Clean extends Command("clean")

  def raiseIfUnxpectedExit(er: ExecutionResult, expected: ExitCode*): IO[Unit] =
    if !expected.contains(ExitCode.fromCode(er.exitCode)) then
      for
        err <- er.debugString
        _ <- IO.raiseError(BazelRunError(err, er.exitCode))
      yield ()
    else IO.unit

  case class BazelRunError(detailsMessage: String, exitCode: Int)
      extends Error(s"Bazel Run Failed: $exitCode\n$detailsMessage")

  sealed trait BazelWrapper(val command: String)
  object BazelWrapper:
    case class At(path: Path) extends BazelWrapper(path.toAbsolutePath.toString)
    case object Default extends BazelWrapper("bazel")

    def default(workspaceRoot: Path): IO[BazelWrapper] =
      IO.blocking {
        List(
          At(workspaceRoot.resolve("bazel-bsp")),
          At(workspaceRoot.resolve("bazel"))
        )
          .find(wr => Files.exists(wr.path))
          .getOrElse(Default)
      }

  def default(
      workspaceRoot: Path,
      bazelWrapper: BazelWrapper,
      logger: Logger
  ): BazelRunner =
    BazelRunnerImpl(workspaceRoot, bazelWrapper, logger)

  private case class BazelRunnerImpl(
      workspaceRoot: Path,
      bazelWrapper: BazelWrapper,
      logger: Logger
  ) extends BazelRunner:

    private def runBazel(
        command: Command,
        expr: Option[String]
    ): Resource[IO, ExecutionResult] =
      for
        _ <- Resource.eval(
          logger.info(
            s"Running ${bazelWrapper.command} ${command.asString} ${expr.getOrElse("_no_args_")}"
          )
        )
        er <- SubProcess
          .from(
            workspaceRoot,
            bazelWrapper.command
          )
          .withArgs(command.asString :: expr.toList)
          .runUntilExit(FiniteDuration(30, TimeUnit.MINUTES))
        _ <- Resource.eval(logger.info(s"Exited with ${er.exitCode}"))
      yield er

    private def runBazelOk(
        command: Command,
        expr: Option[String]
    ): Resource[IO, ExecutionResult] =
      for
        er <- runBazel(command, expr)
        _ <- Resource.eval(BazelRunner.raiseIfUnxpectedExit(er, ExitCode.Ok))
      yield er

    private def runBazel(
        command: Command,
        label: BazelLabel
    ): Resource[IO, ExecutionResult] =
      runBazel(command, Some(label.asString))

    def bspTargets: IO[List[BazelLabel]] =
      runBazelOk(Command.Query, Some("kind('bsp_metadata', //...)"))
        .use { er =>
          er.stdoutLines
            .map(BazelLabel.fromString)
            .collect { case Right(label) => label }
            .compile
            .toList
        }

    def shutdown: IO[Unit] =
      runBazelOk(Command.Shutdown, None).use_

    def clean: IO[Unit] =
      runBazelOk(Command.Clean, None).use_

    private def readBspTarget(target: BazelLabel): IO[BspConfig] =
      val filePath = workspaceRoot
        .resolve("bazel-bin")
        .resolve(target.packagePath.asPath)
        .resolve("bsp_target.json")

      FilesIO.readJson[BspConfig](filePath)

    def bspConfig(target: BazelLabel): IO[BspConfig] =
      runBazel(Command.Build, target).use_ *>
        readBspTarget(target)

    private def diagnostics: Stream[IO, FileDiagnostics] =
      FilesIO
        .walk(
          workspaceRoot.resolve("bazel-bin"),
          Some("*.diagnosticsproto"),
          100
        )
        .flatMap { file =>
          Stream
            .eval {
              FilesIO.readBytes(file).map(TargetDiagnostics.parseFrom)
            }
            .flatMap { td =>
              Stream.fromIterator(
                td.diagnostics.iterator,
                100
              )
            }
        }

    def compile(label: BazelLabel): Stream[IO, FileDiagnostics] =
      Stream
        .eval {
          runBazel(Command.Run, label)
            .use { er =>
              BazelRunner
                .raiseIfUnxpectedExit(
                  er,
                  ExitCode.Ok,
                  ExitCode.BuildFailed
                )
                .as(er.exitCode)
            }
        }
        .flatMap { exitCode =>
          ExitCode.fromCode(exitCode) match
            case ExitCode.BuildFailed => diagnostics
            case _                    => Stream.empty
        }

  end BazelRunnerImpl

  case class BspConfig(
      scalaVersion: String,
      scalacOptions: List[String],
      compileJars: List[Path],
      sources: List[String],
      packages: List[String],
      compileLabel: BazelLabel
  ):
    private lazy val jarMap: Map[String, Path] =
      compileJars.map(p => (p.getFileName.toString, p)).toMap

    def majorScalaVersion: String = scalaVersion match
      case BspConfig.Pattern(major @ "2", minor, patch) => s"$major.$minor"
      case BspConfig.Pattern(major @ "3", minor, patch) => major

  object BspConfig:
    private val Pattern = raw"^(\d+)\.(\d+).(\d+)$$".r

    given Decoder[BspConfig] = Decoder.instance { c =>
      for
        scalaVersion <- c.downField("scala_version").as[String]
        scalacOptions <- c.downField("scalac_options").as[List[String]]
        compileJars <- c.downField("scala_compile_jars").as[List[Path]]
        sources <- c.downField("sources").as[List[String]]
        packages <- c.downField("packages").as[List[String]]
        compileLabel <- c.downField("compile_label").as[BazelLabel]
      yield BspConfig(
        scalaVersion,
        scalacOptions,
        compileJars,
        sources,
        packages,
        compileLabel
      )
    }

    given Decoder[Path] = Decoder[String].map(s => Paths.get(s))
