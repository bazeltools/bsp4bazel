package bazeltools.bsp4bazel

import bazeltools.bsp4bazel.jrpc.JRpcConsoleCodec
import bazeltools.bsp4bazel.jrpc.Message
import bazeltools.bsp4bazel.jrpc.Response
import bazeltools.bsp4bazel.jrpc.RpcFunction
import bazeltools.bsp4bazel.jrpc.jRpcParser
import bazeltools.bsp4bazel.jrpc.messageDispatcher
import bazeltools.bsp4bazel.protocol.BspClient
import bazeltools.bsp4bazel.protocol.BspServer
import bazeltools.bsp4bazel.protocol.TextDocumentIdentifier
import cats.data.NonEmptyList
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.std.Queue
import cats.syntax.all.*
import com.monovore.decline._
import com.monovore.decline.effect._
import fs2.Pipe
import fs2.Stream
import fs2.io.file.Flags
import fs2.text
import io.bazel.rules_scala.diagnostics.diagnostics.FileDiagnostics
import io.circe.Json
import io.circe.syntax.*
import cats.effect.std.Console

import java.net.ServerSocket
import cats.data.Nested
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit
import cats.effect.kernel.Deferred

import bazeltools.bsp4bazel.Bsp4BazelServer
import bazeltools.bsp4bazel.FilesIO
import bazeltools.bsp4bazel.Logger
import bazeltools.bsp4bazel.runner.BazelLabel
import bazeltools.bsp4bazel.runner.BspTaskRunner

object Bsp4BazelApp
    extends CommandIOApp(
      name = "bsp4bazel",
      header = "Bsp 4 Bazel Server",
      version = BuildInfo.version
    ):

  sealed trait Command
  object Command {
    case class Server(verbose: Boolean, packageRoots: NonEmptyList[BazelLabel])
        extends Command
    case object Setup extends Command
  }

  val setupCommand: Opts[Command.Setup.type] =
    Opts.subcommand(name = "setup", help = "Setup Bazel BSP")(
      Opts(Command.Setup)
    )

  val verboseOpt: Opts[Boolean] =
    Opts
      .flag("verbose", help = "Include trace output on stderr")
      .orFalse

  val packageRootOpt: Opts[NonEmptyList[BazelLabel]] =
    Opts
      .options[String](
        "package",
        help =
          "The Bazel package to treat as root. We only look for packages within this subtree. This can be repeated to specify multiple roots.",
        short = "p",
        metavar = "LABEL"
      )
      .map { list =>
        list.map(BazelLabel.fromString(_).fold(throw _, identity))
      }

  val serverCommand: Opts[Command.Server] =
    Opts.subcommand("server", help = "Run the BSP server") {
      (packageRootOpt, verboseOpt).mapN { (packageRoots, verbose) =>
        Command.Server(verbose, packageRoots)
      }
    }

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

  def server(verbose: Boolean, packageRoots: NonEmptyList[BazelLabel])(
      inStream: Stream[IO, String],
      outPipe: Pipe[IO, String, Unit],
      errPipe: Pipe[IO, String, Unit]
  ): IO[ExitCode] =
    for
      loggerStream <- Logger.queue(100, errPipe, verbose)
      (logger, logStream) = loggerStream
      outQ <- Queue.bounded[IO, Message](100)
      client = BspClient.toQueue(outQ, logger)
      server <- Bsp4BazelServer.create(client, logger, packageRoots)
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

  override def main: Opts[IO[ExitCode]] =
    (setupCommand orElse serverCommand).map {
      case Command.Setup => 
        for
          cwd <- FilesIO.cwd
          _ <- BspSetup.writeBspConfig(cwd)
        yield ExitCode.Success
 
      case Command.Server(verbose, packageRoots) =>
        server(verbose, packageRoots)(
          fs2.io.stdinUtf8[IO](10_000),
          fs2.text.utf8.encode.andThen(fs2.io.stdout),
          fs2.text.utf8.encode.andThen(fs2.io.stderr)
        ).handleErrorWith { e =>
          IO.blocking {
            System.err.println(
              s"ERROR: 💣 💣 💣 \n${e.toString} \n${e.getStackTrace.mkString("\n")}"
            )
            ExitCode.Error
          }
        }
    }

