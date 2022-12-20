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

case class BazelSources(sources: List[String], buildFiles: List[String])
object BazelSources:
  given Decoder[BazelSources] = Decoder.instance { c =>
    for
      sources <- c.downField("sources").as[List[String]]
      buildFiles <- c.downField("buildFiles").as[List[String]]
    yield BazelSources(sources, buildFiles)
  }

trait BazelRunner:
  def compile(
      workspaceRoot: Path,
      target: BazelLabel
  ): Stream[IO, FileDiagnostics]
  def clean(workspaceRoot: Path, target: BazelLabel): IO[Unit]
  def targetSources(
      workspaceRoot: Path,
      target: BazelLabel
  ): IO[List[String]]

case class MyBazelRunner(logger: Logger) extends BazelRunner:
  def clean(workspaceRoot: Path, target: BazelLabel): IO[Unit] = ???

  private def runBazel(
      workspaceRoot: Path,
      command: String,
      label: BazelLabel
  ): IO[ExecutionResult] =
    for
      _ <- logger.info(s"Running ./bazel $command ${label.asString}")
      er <- SubProcess.runCommand(
        workspaceRoot,
        "./bazel",
        command,
        label.asString
      )()
      _ <- logger.info(s"Exited with ${er.exitCode}")
    yield er

  private def readSourceFile(
      workspaceRoot: Path,
      sourceTarget: BazelLabel
  ): IO[List[String]] =
    val filePath = workspaceRoot
      .resolve("bazel-bin")
      .resolve(sourceTarget.packagePath.asPath)
      .resolve("sources.json")
    for
      json <- FilesIO.readJson[BazelSources](filePath)
      sources <- json match {
        case Right(bs) => IO.pure(bs.sources)
        case Left(e)   => IO.raiseError(e)
      }
    yield sources

  def targetSources(
      workspaceRoot: Path,
      target: BazelLabel
  ): IO[List[String]] =
    val sourceTarget =
      target.withoutWildcard.withTarget(BazelTarget.Single("sources"))
    for
      er <- runBazel(
        workspaceRoot,
        "build",
        sourceTarget
      )
      fs <- readSourceFile(workspaceRoot, sourceTarget)
    yield fs

  def diagnostics(workspaceRoot: Path): Stream[IO, FileDiagnostics] =
    FilesIO
      .walkTree(
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

  def compile(
      workspaceRoot: Path,
      label: BazelLabel
  ): Stream[IO, FileDiagnostics] =

    val bazelPs =
      Stream.eval(runBazel(workspaceRoot, "build", label))

    bazelPs
      .flatMap { er =>
        diagnostics(workspaceRoot)
      // if er.exitCode != 0 then
      //   Stream.raiseError[IO](
      //     throw new Exception(s"Bazel exited with ${er.exitCode}")
      //   )
      // else diagnostic(base)
      }
