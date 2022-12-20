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

object ServerApp
    extends CommandIOApp(
      name = "bazel-bsp",
      header = "Bazel BSP server",
      version = "0.0.1"
    ):

  val verboseOpt =
    Opts
      .flag("verbose", help = "Include trace output on stderr")
      .orFalse

  def main: Opts[IO[ExitCode]] =
    verboseOpt.map { v =>
      server(v).handleError { case e =>
        System.err.println(
          s"ERROR: 💣 💣 💣 \n${e.toString} \n${e.getStackTrace.mkString("\n")}"
        )
        ExitCode.Error
      }
    }

  def stdInStream(
      bspServer: BspServer,
      stdOutQ: Queue[IO, Message],
      logger: Logger
  ): Stream[IO, Unit] =
    fs2.io
      .stdinUtf8[IO](10_000)
      .evalTap(s => logger.trace(s))
      .through(jRpcParser(logger))
      .evalTap(request => logger.trace(s"request: ${request}"))
      .through(messageDispatcher(BspServer.jsonRpcRouter(bspServer)))
      .evalTap(response => logger.trace(s"response: ${response}"))
      .evalMap {
        case resp: Response =>
          stdOutQ.offer(resp)
        case u: Unit => IO.unit
      }

  def stdErrStream(stdErrQ: Queue[IO, String]): Stream[IO, Unit] =
    Stream
      .fromQueueUnterminated(stdErrQ, 100)
      .evalMap(msg => Console[IO].errorln(msg))

  def stdOutStream(
      stdOutQ: Queue[IO, Message],
      logger: Logger
  ): Stream[IO, Unit] =
    Stream
      .fromQueueUnterminated(stdOutQ, 100)
      .map(msg => JRpcConsoleCodec.encode(msg, false))
      .evalTap(msg => logger.trace(msg))
      .evalMap(msg => Console[IO].print(msg))

  def server(verbose: Boolean): IO[ExitCode] =
    val program = for
      stdErrQ <- Queue.bounded[IO, String](100)
      stdOutQ <- Queue.bounded[IO, Message](100)
      logger = new QueueLogger(stdErrQ, verbose)
      client = new MyBspClient(stdOutQ, logger)
      stateRef <- Ref.of[IO, ServerState](ServerState.default)
      server = new BazelBspServer(
        client,
        logger,
        stateRef
      )
      all = Stream(
        stdInStream(server, stdOutQ, logger),
        stdErrStream(stdErrQ),
        stdOutStream(stdOutQ, logger)
      ).parJoin(3)
      _ <- all.compile.drain
    yield ()

    program.as(ExitCode.Success)
