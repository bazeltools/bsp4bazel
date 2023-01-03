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
import cats.effect.kernel.Ref
import cats.effect.std.Console
import cats.effect.std.Queue
import cats.syntax.all.*
import com.monovore.decline._
import com.monovore.decline.effect._
import fs2.Pipe
import fs2.Stream
import fs2.io.file.Files
import fs2.io.file.Flags
import fs2.io.file.Path
import fs2.text
import io.bazel.rules_scala.diagnostics.diagnostics.FileDiagnostics
import io.circe.Json
import io.circe.syntax.*

import java.net.ServerSocket
import java.nio.file.Paths
import cats.data.Nested

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

  val verifySetupOpt =
    Opts
      .flag(
        "verify",
        help =
          "Verifies that Bazel is correctly configured for use with Bazel BSP"
      )
      .orFalse

  val setupOpt =
    Opts
      .flag("setup", help = "write BSP configuration files into CWD")
      .orFalse

  def printVerifyResult(
      result: List[(String, Either[String, Unit])]
  ): IO[Unit] =
    IO.println(
      result
        .map {
          case (n, Right(_))  => s"✅ $n"
          case (n, Left(err)) => s"❌ $n\n   $err\n"
        }
        .mkString("\n")
    )

  def main: Opts[IO[ExitCode]] =
    (verboseOpt, verifySetupOpt, setupOpt).mapN { (verbose, verify, setup) =>
      if setup then writeBspConfig(BuildMetaData.Version).as(ExitCode.Success)
      else if verify then
        for
          result <- Verifier.validateSetup
          _ <- printVerifyResult(result)
        yield ExitCode.Success
      else
        server(verbose)(
          fs2.io.stdinUtf8[IO](10_000),
          fs2.text.utf8.encode.andThen(fs2.io.stdout),
          fs2.text.utf8.encode.andThen(fs2.io.stderr)
        )
          .handleError { case e =>
            System.err.println(
              s"ERROR: 💣 💣 💣 \n${e.toString} \n${e.getStackTrace.mkString("\n")}"
            )
            ExitCode.Error
          }
    }

  private def writeBspConfig(version: String): IO[Unit] =
    val toPath = fs2.io.file.Path(".bsp/bazel-bsp.json")

    Stream
      .emits(bspConfig(version).getBytes())
      .through(
        fs2.io.file.Files[IO].writeAll(toPath)
      )
      .compile
      .drain
      .flatMap(_ =>
        Console[IO].println(
          s"Write setup config to ${toPath.toNioPath.toAbsolutePath()}"
        )
      )

  private def bspConfig(version: String): String = """
{
    "name": "BazelBsp",
    "version": "${version}",
    "bspVersion": "2.1.0-M1",
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
      // .evalTap(s => logger.trace(s))
      .through(jRpcParser(logger))
      .evalTap(request => logger.trace(s"request: ${request}"))
      .through(messageDispatcher(BspServer.jsonRpcRouter(bspServer)))
      .evalTap(response => logger.trace(s"response: ${response}"))
      .evalMap {
        case resp: Response =>
          outQ.offer(resp)
        case u: Unit => IO.unit
      }

  private def processOutStream(
      outPipe: Pipe[IO, String, Unit],
      outQ: Queue[IO, Message],
      logger: Logger
  ): Stream[IO, Unit] =
    Stream
      .fromQueueUnterminated(outQ, 100)
      .map(msg => JRpcConsoleCodec.encode(msg, false))
      // .evalTap(msg => logger.trace(msg))
      .through(outPipe)

  def server(verbose: Boolean)(
      inStream: Stream[IO, String],
      outPipe: Pipe[IO, String, Unit],
      errPipe: Pipe[IO, String, Unit]
  ): IO[ExitCode] =
    val program = for
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
      _ <- all.compile.drain
    yield ()

    program.as(ExitCode.Success)
