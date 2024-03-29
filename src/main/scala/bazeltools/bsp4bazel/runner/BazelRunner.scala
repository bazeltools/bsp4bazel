package bazeltools.bsp4bazel.runner

import bazeltools.bsp4bazel.FilesIO
import bazeltools.bsp4bazel.Logger
import bazeltools.bsp4bazel.protocol.BspServer
import bazeltools.bsp4bazel.protocol.BuildTargetIdentifier
import bazeltools.bsp4bazel.protocol.TextDocumentIdentifier
import bazeltools.bsp4bazel.protocol.UriFactory
import bazeltools.bsp4bazel.runner.BazelRunner.Command
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

/** Generic Bazel runner (independent of Bsp or any particular being assumed
  * rules)
  */
trait BazelRunner:
  def query(expr: String, options: (String, String)*): IO[BazelResult]
  def build(label: BazelLabel, options: (String, String)*): IO[BazelResult]
  def run(label: BazelLabel, options: (String, String)*): IO[BazelResult]
  def test(label: BazelLabel, options: (String, String)*): IO[BazelResult]

  def clean: IO[Unit]
  def shutdown: IO[Unit]

case class BazelResult(
    exitCode: BazelResult.ExitCode,
    stdout: List[String],
    stderr: List[String]
):
  def debugString =
    s"""Bazel exited with: ${exitCode}
    |stderr:
    |${stderr.mkString("\n")}
    |
    |stdout:
    |${stdout.mkString("\n")}
    |""".stripMargin

object BazelResult:

  private def trim(strings: List[String]): List[String] =
    if strings.isEmpty then Nil
    else
      val buf = strings.toBuffer
      if (buf.head.isEmpty) then buf.dropInPlace(1)
      if (buf.nonEmpty && buf.last.isEmpty) then buf.dropRightInPlace(1)
      buf.toList

  def fromExecutionResult(er: ExecutionResult): IO[BazelResult] =
    for
      so <- er.stdoutLines.compile.toList
      se <- er.stderrLines.compile.toList
    yield BazelResult(ExitCode.fromCode(er.exitCode), trim(so), trim(se))

  enum ExitCode(val code: Int):
    case Ok extends ExitCode(0)
    case BuildFailed extends ExitCode(1)
    case InvalidCommandLine extends ExitCode(2)
    case PartialFailure extends ExitCode(3)
    case NoTestsFound extends ExitCode(4)
    case QueryFailure extends ExitCode(7)
    case BuildInterrupted extends ExitCode(8)
    case ExternalEnvironmentFailure extends ExitCode(32)
    case LocalEnvironmentalIssue extends ExitCode(36)
    case InternalBazelError extends ExitCode(37)
    case UnknownError(n: Int) extends ExitCode(n)

  object ExitCode:
    def fromCode(code: Int): ExitCode =
      code match
        case ExitCode.Ok.code =>
          ExitCode.Ok
        case ExitCode.BuildFailed.code =>
          ExitCode.BuildFailed
        case ExitCode.InvalidCommandLine.code =>
          ExitCode.InvalidCommandLine
        case ExitCode.PartialFailure.code =>
          ExitCode.PartialFailure
        case ExitCode.NoTestsFound.code =>
          ExitCode.NoTestsFound
        case ExitCode.QueryFailure.code =>
          ExitCode.QueryFailure
        case ExitCode.BuildInterrupted.code =>
          ExitCode.BuildInterrupted
        case ExitCode.ExternalEnvironmentFailure.code =>
          ExitCode.ExternalEnvironmentFailure
        case ExitCode.LocalEnvironmentalIssue.code =>
          ExitCode.LocalEnvironmentalIssue
        case ExitCode.InternalBazelError.code =>
          ExitCode.InternalBazelError
        case code =>
          ExitCode.UnknownError(code)

object BazelRunner:

  enum Command(val asString: String):
    case Query extends Command("query")
    case Build extends Command("build")
    case Run extends Command("run")
    case Test extends Command("test")
    case Shutdown extends Command("shutdown")
    case Clean extends Command("clean")

  def raiseIfUnxpectedExit(
      er: ExecutionResult,
      expected: BazelResult.ExitCode*
  ): IO[Unit] =
    if !expected.contains(BazelResult.ExitCode.fromCode(er.exitCode)) then
      er.debugString
        .flatMap { err =>
          IO.raiseError(BazelRunError(err, er.exitCode))
        }
    else IO.unit

  case class BazelRunError(detailsMessage: String, exitCode: Int)
      extends Error(s"Bazel Run Failed: $exitCode\n$detailsMessage")

  enum BazelWrapper(val command: String):
    case At(path: Path) extends BazelWrapper(path.toAbsolutePath.toString)

  def default(
      workspaceRoot: Path,
      logger: Logger,
      bazelWrapper: BazelWrapper
  ): BazelRunner =
    BazelRunnerImpl(workspaceRoot, logger, bazelWrapper)

  def default(
      workspaceRoot: Path,
      logger: Logger
  ): BazelRunner =
    default(workspaceRoot, logger, BazelWrapper.At(workspaceRoot.resolve("bazel")))

  private case class BazelRunnerImpl(
      workspaceRoot: Path,
      logger: Logger,
      bazelWrapper: BazelWrapper
  ) extends BazelRunner:

    private def runBazel(
        command: Command,
        options: List[(String, String)],
        labelOrExpr: String 
    ): Resource[IO, ExecutionResult] = {
      val opts = options.map { case (k, v) => s"$k=$v" }
      runBazel(command, (opts :+ labelOrExpr))
    }

    private def runBazel(
        command: Command,
        args: List[String]
    ): Resource[IO, ExecutionResult] =
      for
        _ <- Resource.eval(
          logger.info(
            s"Running ${bazelWrapper.command} ${command.asString} ${args.mkString(" ")}"
          )
        )
        er <- SubProcess
          .from(
            workspaceRoot,
            bazelWrapper.command
          )
          .withArgs(command.asString :: args)
          .runUntilExit(FiniteDuration(30, TimeUnit.MINUTES))
        _ <- Resource.eval(logger.info(s"Exited with ${er.exitCode}"))
      yield er

    private def runBazelExpectOk(
        command: Command,
        args: List[String]
    ): Resource[IO, ExecutionResult] =
      for
        er <- runBazel(command, args)
        _ <- Resource.eval(
          BazelRunner.raiseIfUnxpectedExit(er, BazelResult.ExitCode.Ok)
        )
      yield er

    def query(expr: String, options: (String, String)*): IO[BazelResult] =
      runBazel(Command.Query, options.toList, expr).use(
        BazelResult.fromExecutionResult(_)
      )

    def build(label: BazelLabel, options: (String, String)*): IO[BazelResult] =
      runBazel(Command.Build, options.toList, label.asString)
        .use(BazelResult.fromExecutionResult(_))

    def run(label: BazelLabel, options: (String, String)*): IO[BazelResult] =
      runBazel(Command.Run, options.toList, label.asString)
        .use(BazelResult.fromExecutionResult(_))

    def test(label: BazelLabel, options: (String, String)*): IO[BazelResult] =
      runBazel(Command.Test, options.toList, label.asString)
        .use(BazelResult.fromExecutionResult(_))

    def shutdown: IO[Unit] =
      runBazelExpectOk(Command.Shutdown, Nil).use_

    def clean: IO[Unit] =
      runBazelExpectOk(Command.Clean, Nil).use_
