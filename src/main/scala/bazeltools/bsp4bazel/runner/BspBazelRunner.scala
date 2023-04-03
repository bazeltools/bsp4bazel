package bazeltools.bsp4bazel.runner

import bazeltools.bsp4bazel.FilesIO
import bazeltools.bsp4bazel.Logger
import bazeltools.bsp4bazel.protocol.BspServer
import bazeltools.bsp4bazel.protocol.BuildTargetIdentifier
import bazeltools.bsp4bazel.protocol.TextDocumentIdentifier
import bazeltools.bsp4bazel.protocol.UriFactory
import cats.effect.IO
import cats.effect.kernel.Resource
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
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.util.Failure
import scala.util.Success
import scala.util.Success.apply
import scala.util.Try

/** Wrapper around BazelRunner for handling `./bazel_rules` rules. Specifically,
  * it handles listing bsp_targets, reading their config, and compiling them
  */
object BspBazelRunner:

  def default(
      workspaceRoot: Path,
      logger: Logger,
      wrapper: BazelRunner.BazelWrapper
  ): BspBazelRunner =
    BspBazelRunner(
      workspaceRoot,
      BazelRunner.default(
        workspaceRoot,
        logger,
        wrapper
      )
    )

  def default(
      workspaceRoot: Path,
      logger: Logger
  ): BspBazelRunner =
    BspBazelRunner(
      workspaceRoot,
      BazelRunner.default(
        workspaceRoot,
        logger
      )
    )

case class BspBazelRunner(workspaceRoot: Path, runner: BazelRunner):

  def bspTargets: IO[List[BspServer.Target]] =
    for result <- runner.query("kind(bsp_target, //...)")
    yield result.stdout
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
    FilesIO.readJson[BazelSources](filePath).map(_.sources)

  def targetSources(target: BazelLabel): IO[List[String]] =
    runner.build(target) *>
      readSourceFile(target)

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
      .eval(runner.build(label.allRulesResursive))
      .flatMap { result =>
        result.exitCode match
          case BazelResult.ExitCode.Ok          => Stream.empty
          case BazelResult.ExitCode.BuildFailed => diagnostics
          case _ =>
            Stream.raiseError(
              new Error(
                s"Bsp Compile task failed: ${result.debugString}"
              )
            )
      }

  case class BazelSources(sources: List[String], buildFiles: List[String])

  object BazelSources:
    given Decoder[BazelSources] = Decoder.instance { c =>
      for
        sources <- c.downField("sources").as[List[String]]
        buildFiles <- c.downField("buildFiles").as[List[String]]
      yield BazelSources(sources, buildFiles)
    }
