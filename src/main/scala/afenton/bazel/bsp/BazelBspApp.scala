package afenton.bazel.bsp

import afenton.bazel.bsp.jrpc.JRpcConsoleCodec
import afenton.bazel.bsp.jrpc.Message
import afenton.bazel.bsp.jrpc.Response
import afenton.bazel.bsp.jrpc.RpcFunction
import afenton.bazel.bsp.jrpc.jRpcParser
import afenton.bazel.bsp.jrpc.messageDispatcher
import afenton.bazel.bsp.protocol.BspClient
import afenton.bazel.bsp.protocol.BspServer
import afenton.bazel.bsp.protocol.TextDocumentIdentifier
import cats.data.NonEmptyList
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.std.Queue
import cats.syntax.all.*
import com.monovore.decline._
import com.monovore.decline.effect._
import fs2.Pipe
import fs2.Stream
import fs2.io.file.Files
import fs2.io.file.Flags
import fs2.text
import io.bazel.rules_scala.diagnostics.diagnostics.FileDiagnostics
import io.circe.Json
import io.circe.syntax.*
import cats.effect.std.Console

import java.net.ServerSocket
import java.nio.file.Path
import java.nio.file.Paths
import cats.data.Nested
import java.nio.file.StandardOpenOption
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit
import cats.effect.kernel.Deferred

object BazelBspApp
    extends CommandIOApp(
      name = "bazel-bsp",
      header = "Bazel BSP server",
      version = BuildMetaData.Version
    ):

  val verboseOpt =
    Opts
      .flag("verbose", help = "Include trace output on stderr")
      .orFalse
      .map { verbose =>
        server(verbose)(
          fs2.io.stdinUtf8[IO](10_000),
          fs2.text.utf8.encode.andThen(fs2.io.stdout),
          fs2.text.utf8.encode.andThen(fs2.io.stderr)
        )
          .handleErrorWith { e =>
            IO.blocking {
              System.err.println(
                s"ERROR: 💣 💣 💣 \n${e.toString} \n${e.getStackTrace.mkString("\n")}"
              )
              ExitCode.Error
            }
          }
      }

  val verifySetupOpt =
    Opts
      .flag(
        "verify",
        help =
          "Verifies that Bazel is correctly configured for use with Bazel BSP"
      )
      .as {
        for
          result <- Verifier.validateSetup
          _ <- printVerifyResult(result)
        yield ExitCode.Success
      }

  val setupOpt =
    Opts
      .flag("setup", help = "write BSP configuration files into CWD")
      .as {
        for
          cwd <- FilesIO.cwd
          _ <- writeBspConfig(cwd)
        yield ExitCode.Success
      }

  def printVerifyResult(
      result: List[(String, Either[String, Unit])]
  ): IO[Unit] =
    result
      .traverse_ {
        case (n, Right(_))  => IO.println(s"✅ $n")
        case (n, Left(err)) => IO.println(s"❌ $n\n   $err\n")
      }

  def main: Opts[IO[ExitCode]] =
    verboseOpt
      .orElse(verifySetupOpt)
      .orElse(setupOpt)

  def writeBspConfig(workspaceRoot: Path): IO[Unit] =
    val toPath = workspaceRoot.resolve(".bsp")

    for
      _ <- Files[IO].createDirectories(toPath)
      _ <- Stream
        .emits(bspConfig.getBytes())
        .through(
          Files[IO].writeAll(
            toPath.resolve("bazel-bsp.json"),
            List(
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING
            )
          )
        )
        .compile
        .drain
      _ <- IO.println(s"Wrote setup config to ${toPath}")
    yield ()

  private lazy val bspConfig: String = s"""
{
    "name": "BazelBsp",
    "version": "${BuildMetaData.Version}",
    "bspVersion": "${BuildMetaData.BspVersion}",
    "languages": [
        "scala"
    ],
    "argv": [
        "bazel-bsp"
    ]
}
"""

  private def processInStream(
      inStream: Stream[IO, String],
      bspServer: BspServer,
      outQ: Queue[IO, Message],
      logger: Logger
  ): Stream[IO, Unit] =
    inStream
      .through(jRpcParser(logger))
      .evalTap(request => logger.trace(s"request: ${request}"))
      .through(messageDispatcher(BspServer.jsonRpcRouter(bspServer)))
      .evalTap(response => logger.trace(s"response: ${response}"))
      .evalMap {
        case resp: Response => outQ.offer(resp)
        case u: Unit        => IO.unit
      }

  private def processOutStream(
      outPipe: Pipe[IO, String, Unit],
      outQ: Queue[IO, Message],
      logger: Logger
  ): Stream[IO, Unit] =
    Stream
      .fromQueueUnterminated(outQ, 100)
      .map(msg => JRpcConsoleCodec.encode(msg, false))
      .through(outPipe)

  def server(verbose: Boolean)(
      inStream: Stream[IO, String],
      outPipe: Pipe[IO, String, Unit],
      errPipe: Pipe[IO, String, Unit]
  ): IO[ExitCode] =
    for
      loggerStream <- Logger.queue(100, errPipe, verbose)
      (logger, logStream) = loggerStream
      outQ <- Queue.bounded[IO, Message](100)
      client = BspClient.toQueue(outQ, logger)
      server <- BazelBspServer.create(client, logger)
      all = Stream(
        processInStream(inStream, server, outQ, logger),
        processOutStream(outPipe, outQ, logger),
        logStream
      ).parJoin(3)
        // NB: Allow time for response to get through
        .interruptWhen(server.exitSignal)
        .onFinalize(Console[IO].errorln("👋 BSP Server Shutting Down"))
      _ <- all.compile.drain
    yield ExitCode.Success
