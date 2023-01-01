package afenton.bazel.bsp

import afenton.bazel.bsp.Lsp.Action
import afenton.bazel.bsp.jrpc.JRpcConsoleCodec
import afenton.bazel.bsp.jrpc.Message
import afenton.bazel.bsp.jrpc.Notification
import afenton.bazel.bsp.jrpc.Request
import afenton.bazel.bsp.jrpc.Response
import afenton.bazel.bsp.jrpc.jRpcParser
import afenton.bazel.bsp.jrpc.messageDispatcher
import afenton.bazel.bsp.protocol.BuildClientCapabilities
import afenton.bazel.bsp.protocol.BuildTargetIdentifier
import afenton.bazel.bsp.protocol.CompileParams
import afenton.bazel.bsp.protocol.InitializeBuildParams
import afenton.bazel.bsp.protocol.InitializeBuildResult
import afenton.bazel.bsp.protocol.UriFactory
import afenton.bazel.bsp.runner.SubProcess
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

import java.nio.file.Path
import java.nio.file.Paths
import scala.concurrent.duration.FiniteDuration

type OpenRequests = Map[String, Deferred[IO, Response]]

/** A cient interface to the BSP server.
  */
case class BspClient(
    openRequestsRef: Ref[IO, OpenRequests],
    bspInQ: Queue[IO, String],
    counter: Ref[IO, Int]
):

  private def sendRequest[A: Encoder, B: Encoder: Decoder](
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

  // def buildShutdown(params: Unit): IO[Unit]
  // def buildExit(params: Unit): IO[Unit]

  // def workspaceBuildTargets(params: Unit): IO[WorkspaceBuildTargetsResult]

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

/** Simulates client requests. Intended for test use only
  */
case class LspTestProcess(workspaceRoot: Path):

  def pipeInto(q: Queue[IO, String]): Pipe[IO, String, Unit] = {
    (stream: Stream[IO, String]) => stream.evalMap(s => q.offer(s))
  }

  private val logger: Logger = Logger.noOp

  private def processBspOut(
      outStream: Stream[IO, String],
      openRequestsRef: Ref[IO, OpenRequests],
      duration: FiniteDuration
  ): IO[List[Notification]] =
    outStream
      .through(jRpcParser(logger))
      .evalFilter {
        case resp @ Response(jsonrpc, id, result, error) =>
          for
            or <- openRequestsRef.get
            deferred = or.get(id.get.toString).get
            result <- deferred
              .complete(resp)
              .ifM(
                IO.unit,
                IO.raiseError(new Exception("Response completed twice"))
              )
          yield false

        case n: Notification => IO.pure(true)

        case _ => IO.raiseError(new Exception("Shouldn't get here"))
      }
      .collect { case n: Notification => n }
      .interruptAfter(duration)
      .compile
      .toList

  private def processBspErr(
      errStream: Stream[IO, String],
      duration: FiniteDuration
  ): IO[Unit] =
    errStream
      .evalMap(str => Console[IO].errorln(str))
      .interruptAfter(duration)
      .compile
      .drain

  private def processActions(
      client: BspClient,
      actions: List[Lsp.Action]
  ): IO[List[Matchable]] =
    for results <- actions.map {
        case Action.Start           => start(client)
        case Action.Compile(target) => compile(client, target)
      }.sequence
    yield results

  def runFor(
      actions: List[Lsp.Action],
      duration: FiniteDuration
  ): IO[(List[Matchable], List[Notification])] =
    for
      bspOutQ <- Queue.bounded[IO, String](1_000)
      bspErrQ <- Queue.bounded[IO, String](1_000)
      bspInQ <- Queue.bounded[IO, String](1_000)
      bspServer = BazelBspApp.server(true)(
        Stream.fromQueueUnterminated(bspInQ),
        pipeInto(bspOutQ),
        pipeInto(bspErrQ)
      )
      openRequests <- Ref.of[IO, OpenRequests](Map.empty)
      counter <- Ref.of[IO, Int](1)
      client = BspClient(openRequests, bspInQ, counter)
      result = IO.both(
        processActions(client, actions),
        (processBspOut(
          Stream.fromQueueUnterminated(bspOutQ),
          openRequests,
          duration
        ) <& processBspErr(Stream.fromQueueUnterminated(bspErrQ), duration))
      )
      out <- IO.race(bspServer, result)
    yield out.right.get

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
 
  extension [A](io: IO[A])
    def marker(str: String) = io.map { a => System.err.println(str + s" WITH: $a");  a }

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
      ).marker("AFTER COMPILE IN LSP")
      resp <- dResp.get
    yield resp

case class Lsp(actions: Vector[Lsp.Action]):

  def :+(a: Lsp.Action): Lsp = copy(actions :+ a)
  def start: Lsp = 
    this :+ Lsp.Action.Start

  def compile(target: String): Lsp =
    this :+ Lsp.Action.Compile(BuildTargetIdentifier.bazel(target)) 

  def runFor(
      workspaceRoot: Path,
      duration: FiniteDuration
  ): IO[(List[Matchable], List[Notification])] =
    LspTestProcess(workspaceRoot).runFor(actions.toList, duration)

object Lsp:

  def start: Lsp = Lsp(Vector.empty).start

  sealed trait Action
  object Action:
    case object Start extends Action
    case class Compile(target: BuildTargetIdentifier) extends Action
