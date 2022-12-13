package afenton.bazel.bsp

import afenton.bazel.bsp.jrpc.JRpcConsoleCodec
import afenton.bazel.bsp.jrpc.Message
import afenton.bazel.bsp.jrpc.Response
import afenton.bazel.bsp.jrpc.RpcFunction
import afenton.bazel.bsp.jrpc.jRpcParser
import afenton.bazel.bsp.jrpc.messageDispatcher
import cats.data.NonEmptyList
import cats.effect.ExitCode
import cats.effect.IO
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
import io.circe.Json
import io.circe.syntax.*

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
      server(v)
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
      .evalMap(resp => stdOutQ.offer(resp))
      .evalMap(s => IO {})

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
      server = new BazelBspServer(client, logger)
      all = Stream(
        stdInStream(server, stdOutQ, logger),
        stdErrStream(stdErrQ),
        stdOutStream(stdOutQ, logger)
      ).parJoin(3)
      _ <- all.compile.drain
    yield ()

    program.as(ExitCode.Success)
