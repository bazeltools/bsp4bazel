package bazeltools.bsp4bazel

import bazeltools.bsp4bazel.jrpc.JRpcConsoleCodec
import bazeltools.bsp4bazel.jrpc.Message
import bazeltools.bsp4bazel.jrpc.Notification
import bazeltools.bsp4bazel.jrpc.Request
import bazeltools.bsp4bazel.jrpc.Response
import bazeltools.bsp4bazel.jrpc.jRpcParser
import bazeltools.bsp4bazel.jrpc.messageDispatcher
import bazeltools.bsp4bazel.protocol.BuildClientCapabilities
import bazeltools.bsp4bazel.protocol.BuildTargetIdentifier
import bazeltools.bsp4bazel.protocol.CompileParams
import bazeltools.bsp4bazel.protocol.InitializeBuildParams
import bazeltools.bsp4bazel.protocol.InitializeBuildResult
import bazeltools.bsp4bazel.protocol.UriFactory
import bazeltools.bsp4bazel.runner.SubProcess
import cats.Functor
import cats.effect.IO
import cats.effect.kernel.Deferred
import cats.effect.kernel.DeferredSource
import cats.effect.kernel.Ref
import cats.effect.std.Console
import cats.effect.std.MapRef
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Pipe
import fs2.Stream
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.syntax.*
import cats.data.NonEmptyList

import java.nio.file.Path
import java.nio.file.Paths
import scala.concurrent.duration.FiniteDuration

import bazeltools.bsp4bazel.IOLifts.{asIO, mapToIO}
import bazeltools.bsp4bazel.protocol.WorkspaceBuildTargetsResult

import bazeltools.bsp4bazel.Bsp4BazelApp
import bazeltools.bsp4bazel.IOLifts
import bazeltools.bsp4bazel.Logger
import bazeltools.bsp4bazel.runner.BazelLabel
type OpenRequests = Map[String, Deferred[IO, Response]]

/** A cient interface to the BSP server.
  */
case class BspClient(
    openRequestsRef: Ref[IO, OpenRequests],
    bspInQ: Queue[IO, String],
    counter: Ref[IO, Int]
):
  private def sendNotification[A: Encoder](
      method: String,
      params: A
  ): IO[Unit] =
    val notification =
      Notification("2.0", method, Some(Encoder[A].apply(params)))
    bspInQ.offer(JRpcConsoleCodec.encode(notification, false))

  private def sendRequest[A: Encoder, B: Decoder](
      method: String,
      params: A
  )(using ev: BspClient.IsUnit[B]): IO[DeferredSource[IO, B]] =
    val jsonParams = Encoder[A].apply(params)
    for
      id <- counter.getAndUpdate(_ + 1)
      dResp <- sendRequest(Request("2.0", id, method, Some(jsonParams)))
    yield dResp.map { (resp: Response) =>
      resp.result match
        case Some(json)        => Decoder[B].decodeJson(json).toTry.get
        case None if ev.isUnit => ev.coerce.get
        case _ =>
          throw Exception("Was expecting 'result' json, but got nothing")
    }

  private def openRequest(request: Request): IO[DeferredSource[IO, Response]] =
    for
      newDefer <- IO.deferred[Response]
      _ <- openRequestsRef.update { orr =>
        orr + (request.id.toString -> newDefer)
      }
    yield newDefer

  private def sendRequest(request: Request): IO[DeferredSource[IO, Response]] =
    for
      _ <- Stream
        .emit(JRpcConsoleCodec.encode(request, false))
        .evalMap(s => bspInQ.offer(s))
        .compile
        .drain
      defer <- openRequest(request)
    yield defer

  def buildInitialize(
      params: InitializeBuildParams
  ): IO[DeferredSource[IO, InitializeBuildResult]] =
    sendRequest("build/initialize", params)

  def buildInitialized(params: Unit): IO[DeferredSource[IO, Unit]] =
    sendRequest("build/initialized", params)

  def buildShutdown(params: Unit): IO[DeferredSource[IO, Unit]] =
    sendRequest("build/shutdown", params)

  def buildExit(params: Unit): IO[Unit] =
    sendNotification("build/exit", params)

  def workspaceBuildTargets(
      params: Unit
  ): IO[DeferredSource[IO, WorkspaceBuildTargetsResult]] =
    sendRequest("workspace/buildTargets", params)

  def buildTargetCompile(params: CompileParams): IO[DeferredSource[IO, Unit]] =
    sendRequest("buildTarget/compile", params)

  // def buildTargetScalacOptions(
  //     params: ScalacOptionsParams
  // ): IO[ScalacOptionsResult]
  // def buildTargetJavacOptions(
  //     params: JavacOptionsParams
  // ): IO[JavacOptionsResult]
  // def buildTargetSource(params: SourcesParams): IO[SourcesResult]
  // def buildTargetDependencySources(
  //     params: DependencySourcesParams
  // ): IO[DependencySourcesResult]
  // def buildTargetScalaMainClasses(
  //     params: ScalaMainClassesParams
  // ): IO[ScalaMainClassesResult]
  // def buildTargetInverseSources(
  //     params: InverseSourcesParams
  // ): IO[InverseSourcesResult]
  // def buildTargetCleanCache(params: CleanCacheParams): IO[CleanCacheResult]
  // def cancelRequest(params: CancelParams): IO[Unit]

object BspClient:
  // NB. Seems a round about way to do this. Is there something better to use in Scala3?
  sealed trait IsUnit[A]:
    def isUnit: Boolean
    def coerce: Option[A]

  object IsUnit:
    given IsUnit[Unit] = new IsUnit[Unit] {
      def isUnit: Boolean = true
      def coerce: Option[Unit] = Some(())
    }
    given [A]: IsUnit[A] = new IsUnit[A] {
      def isUnit: Boolean = false
      def coerce: Option[A] = None
    }

type Result = InitializeBuildResult | WorkspaceBuildTargetsResult | Unit

/** Simulates client requests. Intended for test use only
  */
case class LspTestProcess(workspaceRoot: Path):

  def pipeInto(q: Queue[IO, String]): Pipe[IO, String, Unit] = {
    (stream: Stream[IO, String]) => stream.evalMap(s => q.offer(s))
  }

  private val logger: Logger = Logger.noOp

  private def checkOutputType: Pipe[IO, Message, Response | Notification] = {
    (stream: Stream[IO, Message]) =>
      stream
        .evalMap {
          case n: Notification => IO.pure(n)
          case resp: Response  => IO.pure(resp)
          case req: Request =>
            IO.raiseError(
              new Exception(s"Didn't expect to get a request here. Got $req")
            )
        }
  }

  private def processBspOut(
      outStream: Stream[IO, String],
      openRequestsRef: Ref[IO, OpenRequests],
      exitSwitch: Deferred[IO, Either[Throwable, Unit]]
  ): IO[List[Notification]] =
    outStream
      .through(jRpcParser(logger))
      .through(checkOutputType)
      .evalFilter {
        case resp @ Response(_, id, _, _) =>
          for
            or <- openRequestsRef.get
            deferred <- id.mapToIO { idRes =>
              or.get(idRes.toString).asIO
            }
            _ <- deferred
              .complete(resp)
              .ifM(
                IO.unit,
                IO.raiseError(new Exception("Response completed twice"))
              )
          yield false

        case _: Notification => IO.pure(true)
      }
      .interruptWhen(exitSwitch)
      .collect { case n: Notification => n }
      .compile
      .toList

  private def processBspErr(errStream: Stream[IO, String]): IO[Unit] =
    errStream
      .evalMap(str => Console[IO].errorln(str))
      .compile
      .drain

  private def processActions(
      client: BspClient,
      actions: List[Lsp.Action],
      exitSwitch: Deferred[IO, Either[Throwable, Unit]]
  ): IO[List[Result]] =
    actions.traverse {
      case Lsp.Action.Start            => start(client)
      case Lsp.Action.Shutdown         => shutdown(client, exitSwitch)
      case Lsp.Action.WorkspaceTargets => workspaceTargets(client)
      case Lsp.Action.Compile(target)  => compile(client, target)
    }

  def runIn(
      actions: List[Lsp.Action]
  ): IO[
    (
        List[Result],
        List[Notification]
    )
  ] =
    for
      bspOutQ <- Queue.bounded[IO, String](1_000)
      bspErrQ <- Queue.bounded[IO, String](1_000)
      bspInQ <- Queue.bounded[IO, String](1_000)
      bspServer = Bsp4BazelApp.server(true, NonEmptyList.one(BazelLabel.fromStringUnsafe("//...")))(
        Stream.fromQueueUnterminated(bspInQ),
        pipeInto(bspOutQ),
        pipeInto(bspErrQ)
      )
      openRequests <- Ref.of[IO, OpenRequests](Map.empty)
      counter <- Ref.of[IO, Int](1)
      client = BspClient(openRequests, bspInQ, counter)
      exitSwitch <- Deferred[IO, Either[Throwable, Unit]]

      fibErr <- processBspErr(Stream.fromQueueUnterminated(bspErrQ)).start
      fibServer <- bspServer.start

      bspOutIO = processBspOut(
        Stream.fromQueueUnterminated(bspOutQ),
        openRequests,
        exitSwitch
      )

      respNotes <- IO.both(
        processActions(client, actions, exitSwitch),
        bspOutIO
      )
      (resp, notifications) = respNotes
      _ <- fibErr.cancel
      _ <- fibServer.cancel
    yield (resp, notifications)

  private def start(client: BspClient): IO[InitializeBuildResult] =
    for
      d1 <- client.buildInitialize(
        InitializeBuildParams(
          "test",
          "0.1",
          "2.1",
          BuildClientCapabilities(List("scala")),
          UriFactory.fileUri(workspaceRoot),
          None
        )
      )
      resp <- d1.get
      d2 <- client.buildInitialized(())
      _ <- d2.get
    yield resp

  private def shutdown(
      client: BspClient,
      exitSwitch: Deferred[IO, Either[Throwable, Unit]]
  ): IO[Unit] =
    for
      d1 <- client.buildShutdown(())
      _ <- d1.get
      _ <- client.buildExit(())
      _ <- exitSwitch.complete(Right(()))
    yield ()

  private def workspaceTargets(
      client: BspClient
  ): IO[WorkspaceBuildTargetsResult] =
    for
      dResp <- client.workspaceBuildTargets(())
      resp <- dResp.get
    yield resp

  private def compile(
      client: BspClient,
      target: BuildTargetIdentifier
  ): IO[Unit] =
    for
      dResp <- client.buildTargetCompile(
        CompileParams(
          List(target),
          None,
          None
        )
      )
      resp <- dResp.get
    yield resp

case class Lsp(actions: Vector[Lsp.Action]):

  def :+(a: Lsp.Action): Lsp = copy(actions :+ a)

  def start: Lsp =
    this :+ Lsp.Action.Start

  def compile(target: String): Lsp =
    val label = BazelLabel.fromString(target).toOption.get
    this :+ Lsp.Action.Compile(BuildTargetIdentifier.bazel(label))

  def workspaceTargets: Lsp =
    this :+ Lsp.Action.WorkspaceTargets

  def shutdown: Lsp =
    this :+ Lsp.Action.Shutdown

  def runIn(
      workspaceRoot: Path
  ): IO[
    (
        List[Result],
        List[Notification]
    )
  ] =
    LspTestProcess(workspaceRoot).runIn(actions.toList)

object Lsp:

  def start: Lsp = Lsp(Vector.empty).start

  enum Action:
    case Start
    case Shutdown
    case Compile(target: BuildTargetIdentifier)
    case WorkspaceTargets
