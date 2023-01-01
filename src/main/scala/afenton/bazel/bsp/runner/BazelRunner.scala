package afenton.bazel.bsp.runner

import afenton.bazel.bsp.protocol.BuildTargetIdentifier
import afenton.bazel.bsp.protocol.TextDocumentIdentifier
import afenton.bazel.bsp.protocol.UriFactory
import cats.effect.IO
import cats.syntax.all._
import fs2.Stream
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
import scala.jdk.CollectionConverters._
import scala.util.Failure
import scala.util.Success
import scala.util.Success.apply
import scala.util.Try
import afenton.bazel.bsp.FilesIO
import afenton.bazel.bsp.Logger
import scala.concurrent.duration.FiniteDuration
import afenton.bazel.bsp.protocol.BspServer

case class BazelSources(sources: List[String], buildFiles: List[String])
object BazelSources:
  given Decoder[BazelSources] = Decoder.instance { c =>
    for
      sources <- c.downField("sources").as[List[String]]
      buildFiles <- c.downField("buildFiles").as[List[String]]
    yield BazelSources(sources, buildFiles)
  }

trait BazelRunner:
  def compile(target: BazelLabel): Stream[IO, FileDiagnostics]
  def clean: IO[Unit]
  def shutdown: IO[Unit]
  def targetSources(target: BazelLabel): IO[List[String]]
  def bspTargets: IO[List[BspServer.Target]]

object BazelRunner:
  val Ok: Int = 0
  val BuildFailed: Int = 1

  def raiseIfUnxpectedExit(er: ExecutionResult, expected: Int*): IO[Unit] =
    if !expected.contains(er.exitCode) then
      for
        err <- er.debugString
        _ <- IO.raiseError(BazelRunError(err, er.exitCode))
      yield ()
    else IO.unit

  case class BazelRunError(detailsMessage: String, exitCode: Int)
      extends Error(s"Bazel Run Failed: $exitCode\n$detailsMessage")

  def default(workspaceRoot: Path, logger: Logger): BazelRunner =
    BazelRunnerImpl(workspaceRoot, logger)

  private case class BazelRunnerImpl(workspaceRoot: Path, logger: Logger)
      extends BazelRunner:

    def shutdown: IO[Unit] =
      for
        er <- runBazel("shutdown", None)
        _ <- BazelRunner.raiseIfUnxpectedExit(er, BazelRunner.Ok)
      yield ()

    def clean: IO[Unit] =
      for
        er <- runBazel("clean", None)
        _ <- BazelRunner.raiseIfUnxpectedExit(er, BazelRunner.Ok)
      yield ()

    private def runBazel(
        command: String,
        expr: Option[String]
    ): IO[ExecutionResult] =
      for
        _ <- logger.info(
          s"Running ./bazel $command ${expr.getOrElse("_no_args_")}"
        )
        er <- SubProcess
          .from(
            workspaceRoot,
            "./bazel"
          )
          .withArgs(command :: expr.toList)
          .runUntilExit
          .timeout(FiniteDuration(30, TimeUnit.SECONDS))
        _ <- logger.info(s"Exited with ${er.exitCode}")
      yield er

    private def runBazel(
        command: String,
        label: BazelLabel
    ): IO[ExecutionResult] =
      runBazel(command, Some(label.asString))

    def bspTargets: IO[List[BspServer.Target]] =
      for
        er <- runBazel("query", Some("kind(bsp_target, //...)"))
        stdout <- er.stdoutLines
        _ <- BazelRunner.raiseIfUnxpectedExit(er, BazelRunner.Ok)
      yield stdout.toList
        .map(BazelLabel.fromString)
        .collect { case Right(label) =>
          BspServer.Target(
            UriFactory.bazelUri(label.asString),
            label.target.map(_.asString).getOrElse(label.asString)
          )
        }

    private def readSourceFile(target: BazelLabel): IO[List[String]] =
      val filePath = workspaceRoot
        .resolve("bazel-bin")
        .resolve(target.packagePath.asPath)
        .resolve("sources.json")
      for
        json <- FilesIO.readJson[BazelSources](filePath)
        sources <- json match {
          case Right(bs) => IO.pure(bs.sources)
          case Left(e)   => IO.raiseError(e)
        }
      yield sources

    def targetSources(target: BazelLabel): IO[List[String]] =
      for
        er <- runBazel("build", target)
        fs <- readSourceFile(target)
      yield fs

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
              FilesIO.readBytes(file).rethrow.map(TargetDiagnostics.parseFrom)
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
          for
            er <- runBazel("build", label.allRulesResursive)
            _ <- BazelRunner.raiseIfUnxpectedExit(
              er,
              BazelRunner.Ok,
              BazelRunner.BuildFailed
            )
          yield er
        }
        .flatMap { er =>
          er.exitCode match
            case BazelRunner.BuildFailed =>
              diagnostics
            case _ => Stream.empty
        }
