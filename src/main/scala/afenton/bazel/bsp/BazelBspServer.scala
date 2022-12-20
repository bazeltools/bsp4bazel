package afenton.bazel.bsp

import afenton.bazel.bsp.jrpc.JRpcClient
import afenton.bazel.bsp.jrpc.Message
import afenton.bazel.bsp.jrpc.Notification
import afenton.bazel.bsp.protocol.*
import afenton.bazel.bsp.runner.MyBazelRunner
import afenton.bazel.bsp.runner.BazelLabel
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Queue
import cats.syntax.all._
import io.bazel.rules_scala.diagnostics.diagnostics.FileDiagnostics
import io.circe.syntax._

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import io.circe.Json
import io.circe.Decoder
import io.circe.DecodingFailure

case class ServerState(
    targetSourceMap: TargetSourceMap,
    currentErrors: List[FileDiagnostics],
    workspaceRoot: Option[Path],
    targets: List[BuildTarget]
)
object ServerState:
  def default: ServerState = ServerState(TargetSourceMap.empty, Nil, None, Nil)

class BazelBspServer(
    client: BspClient,
    logger: Logger,
    stateRef: Ref[IO, ServerState]
) extends BspServer(client):

  private val version = "0.1"
  private val bspVersion = "2.0.0-M2"

  private val bazelRunner = MyBazelRunner(logger)

  def buildInitialize(
      params: InitializeBuildParams
  ): IO[InitializeBuildResult] =
    for
      _ <- logger.info("buildInitialize")
      workspaceRoot = Paths.get(params.rootUri)
      _ <- stateRef.update(_.copy(workspaceRoot = Some(workspaceRoot)))
      resp <- IO.pure {
        val compileProvider = CompileProvider(List("scala"))

        InitializeBuildResult(
          "Bazel",
          version,
          bspVersion,
          BuildServerCapabilities(
            compileProvider = Some(compileProvider),
            inverseSourcesProvider = Some(true),
            canReload = Some(true)
          )
        )
      }
    yield resp

  def buildInitialized(params: Unit): IO[Unit] =
    for
      _ <- logger.info("build/initialized")
      state <- stateRef.get
      ws <- workspaceBuildTargets(())
      ts <- doBuildTargetSources(
        state.workspaceRoot.get,
        ws.targets.map(_.id)
      )
      _ <- stateRef.update(s => s.copy(targetSourceMap = TargetSourceMap(ts)))
    yield ()

  def buildTargetInverseSources(
      params: InverseSourcesParams
  ): IO[InverseSourcesResult] =
    for
      state <- stateRef.get
      _ <- logger.trace("buildTarget/inverseSources")
      state <- stateRef.get
    yield
      val relativeUri =
        UriFactory.fileUri(
          state.workspaceRoot.get.relativize(Paths.get(params.textDocument.uri))
        )

      InverseSourcesResult(
        state.targetSourceMap.targetsForSource(
          TextDocumentIdentifier(relativeUri)
        )
      )

  private def buildTarget(
      id: URI,
      displayName: String,
      workspaceRoot: Path
  ): BuildTarget =
    BuildTarget(
      BuildTargetIdentifier(id),
      Some(displayName),
      Some(UriFactory.fileUri(workspaceRoot)),
      List("library"),
      BuildTargetCapabilities(true, false, false, false),
      List("scala"),
      Nil,
      Some("scala"),
      Some(
        ScalaBuilderTarget(
          "org.scala-lang",
          "2.12",
          "2.12",
          ScalaPlatform.JVM,
          Nil,
          None
        ).asJson
      )
    )

  case class Target(id: URI, name: String)
  object Target:
    given Decoder[List[Target]] = Decoder.instance { c =>
      c.keys match {
        case Some(ks) =>
          ks.map { k =>
            c.downField(k).as[String].map { v =>
              Target(UriFactory.bazelUri(v), k)
            }
          }.toList
            .sequence
        case None =>
          Left(
            DecodingFailure(
              s"Expected Json Object instead got ${c.value}",
              c.history
            )
          )
      }
    }

  private def readTargetConfig: IO[Either[Throwable, List[BuildTarget]]] =
    for
      state <- stateRef.get
      json <- FilesIO.readJson[Json](
        state.workspaceRoot.get.resolve(".bazelBspTargets.json")
      )
    yield json match
      case Right(json) =>
        json
          .as[List[Target]]
          .map(_.map(t => buildTarget(t.id, t.name, state.workspaceRoot.get)))
      case Left(e) => Left(e)

  def workspaceBuildTargets(params: Unit): IO[WorkspaceBuildTargetsResult] =
    for
      _ <- logger.info("workspace/buildTargets")
      config <- readTargetConfig
      wbt <- config match {
        case Right(bts) => IO(WorkspaceBuildTargetsResult(bts))
        case Left(err)  => IO.raiseError(err)
      }
    yield wbt

  def buildTargetScalacOptions(
      params: ScalacOptionsParams
  ): IO[ScalacOptionsResult] =
    for
      _ <- logger.info("buildTarget/scalacOptions")
      resp = ScalacOptionsResult(Nil)
    yield resp

  def buildTargetJavacOptions(
      params: JavacOptionsParams
  ): IO[JavacOptionsResult] =
    for
      _ <- logger.info("buildTarget/javacOptions")
      resp = JavacOptionsResult(Nil)
    yield resp

  private def doCompile(
      workspaceRoot: Path,
      target: BuildTargetIdentifier,
      id: TaskId
  ): IO[List[FileDiagnostics]] =
    BazelLabel.fromBuildTargetIdentifier(target) match {
      case Right(bazelLabel) =>
        bazelRunner
          .compile(workspaceRoot, bazelLabel)
          .filterNot(fd => fd.path.toString.endsWith("<no file>"))
          .evalTap { fd =>
            for
              time <- IO.realTimeInstant
              _ <- client.buildTaskProgress(
                TaskProgressParams(
                  id,
                  Some(time.toEpochMilli),
                  Some("Compile In Progress"),
                  Some(50),
                  Some("files"),
                  None,
                  None
                )
              )
            yield ()
          }
          .evalTap { fd =>
            val pd = PublishDiagnosticsParams.fromScalacDiagnostic(
              workspaceRoot,
              target,
              fd
            )
            client.publishDiagnostics(pd)
          }
          .compile
          .toList
      case Left(err) => IO.raiseError(err)
    }

  // If we don't get back any FileDiagnostics, we assume there's now no errors, so have to
  // clear these out by publishing an empty Diagnostic for them
  private def clearPrevDiagnostics(
      target: BuildTargetIdentifier,
      fds: List[FileDiagnostics]
  ): IO[Unit] =
    for
      state <- stateRef.get
      pathSet = fds.map(fs => fs.path).toSet
      clearDiagnostics = state.currentErrors
        .filterNot(fd => pathSet.contains(fd.path))
        .map(_.clearDiagnostics)
        .map(fd =>
          PublishDiagnosticsParams
            .fromScalacDiagnostic(state.workspaceRoot.get, target, fd)
        )
      _ <- clearDiagnostics.map(client.publishDiagnostics).sequence_
      _ <- stateRef.update(_.copy(currentErrors = fds))
    yield ()

  private def compileTarget(target: BuildTargetIdentifier): IO[Unit] =
    for
      state <- stateRef.get
      id <- IO.randomUUID.map(u => TaskId(u.toString, None))
      start <- IO.realTimeInstant
      _ <- client.buildTaskStart(
        TaskStartParams(
          id,
          Some(start.toEpochMilli),
          Some("Compile Started"),
          Some(TaskDataKind.CompileTask),
          Some(CompileTask(target).asJson)
        )
      )
      fds <- doCompile(state.workspaceRoot.get, target, id)
      _ <- clearPrevDiagnostics(target, fds)
      end <- IO.realTimeInstant
      _ <- client.buildTaskFinished(
        TaskFinishParams(
          id,
          Some(end.toEpochMilli),
          Some("Compile Finished"),
          StatusCode.Ok,
          Some(TaskDataKind.CompileReport),
          Some(CompileReport(target, None, 1, 1, None, None).asJson)
        )
      )
    yield ()

  def buildTargetCompile(params: CompileParams): IO[Unit] =
    for
      _ <- logger.info(
        s"Compiling targets: ${params.targets.map(_.uri.toString).mkString(",")}"
      )
      _ <- params.targets.map(compileTarget).sequence_
    yield ()

  def buildShutdown(params: Unit): IO[Unit] =
    for
      _ <- logger.info("build/shutdown")
      _ <- IO.raiseError(new Exception("SHUTTING DOWN"))
    yield ()

  def buildExit(params: Unit): IO[Unit] =
    for
      _ <- logger.info("build/exit")
      _ <- IO.canceled
    yield ()

  private def doBuildTargetSources(
      workspaceRoot: Path,
      targets: List[BuildTargetIdentifier]
  ): IO[Map[BuildTargetIdentifier, List[TextDocumentIdentifier]]] =
    targets
      .map { bt =>
        BazelLabel.fromBuildTargetIdentifier(bt) match
          case Right(bazelTarget) =>
            bazelRunner
              .targetSources(workspaceRoot, bazelTarget)
              .map(ss =>
                (bt, ss.map(s => TextDocumentIdentifier(UriFactory.fileUri(s))))
              )
          case Left(err) =>
            IO.raiseError(err)
      }
      .sequence
      .map(_.toMap)

  def buildTargetSource(params: SourcesParams): IO[SourcesResult] =
    for
      _ <- logger.info("buildTarget/sources")
      state <- stateRef.get
    yield
      val sourcesItem = params.targets.map { target =>
        val sources = state.targetSourceMap
          .sourcesForTarget(target)
          .map(td => SourceItem(td.uri, SourceItemKind.File, false))
        SourcesItem(
          target,
          sources,
          Some(List(UriFactory.fileUri(state.workspaceRoot.get).toString))
        )
      }
      SourcesResult(sourcesItem)

  def buildTargetDependencySources(
      params: DependencySourcesParams
  ): IO[DependencySourcesResult] =
    for
      _ <- logger.info("buildTarget/dependencySources")
      resp = DependencySourcesResult(Nil)
    yield resp

  def buildTargetScalaMainClasses(
      params: ScalaMainClassesParams
  ): IO[ScalaMainClassesResult] =
    for
      _ <- logger.info("buildTarget/scalaMainClasses")
      resp = ScalaMainClassesResult(Nil, None)
    yield resp

  def buildTargetCleanCache(params: CleanCacheParams): IO[CleanCacheResult] =
    for _ <- logger.info("buildTarget/cleanCache")
    yield CleanCacheResult(Some("Cleaned"), true)

  def cancelRequest(params: CancelParams): IO[Unit] =
    for _ <- logger.info("$/cancelRequest")
    yield ()

end BazelBspServer

class MyBspClient(stdOutQ: Queue[IO, Message], logger: Logger)
    extends BspClient
    with JRpcClient:

  def sendNotification(n: Notification): IO[Unit] =
    for
      _ <- logger.info(n.method)
      _ <- stdOutQ.offer(n)
    yield ()

  def publishDiagnostics(params: PublishDiagnosticsParams): IO[Unit] =
    sendNotification("build/publishDiagnostics", params)

  def buildTaskStart(params: TaskStartParams): IO[Unit] =
    sendNotification("build/taskStart", params)

  def buildTaskProgress(params: TaskProgressParams): IO[Unit] =
    sendNotification("build/taskProgress", params)

  def buildTaskFinished(params: TaskFinishParams): IO[Unit] =
    sendNotification("build/taskFinish", params)

  def buildShowMessage(params: ShowMessageParams): IO[Unit] =
    sendNotification("build/showMessage", params)

case class TargetSourceMap(
    val _targetSources: Map[BuildTargetIdentifier, List[
      TextDocumentIdentifier
    ]]
):

  private def invertMap[K, V](map: Map[K, List[V]]): Map[V, List[K]] =
    map.toList
      .flatMap((bt, ls) => ls.map(l => (l, bt)))
      .groupMap(_._1)(_._2)

  private val sourceTargets
      : Map[TextDocumentIdentifier, List[BuildTargetIdentifier]] =
    invertMap(_targetSources)

  def sourcesForTarget(
      bt: BuildTargetIdentifier
  ): List[TextDocumentIdentifier] =
    _targetSources.get(bt).getOrElse(Nil)

  def targetsForSource(
      td: TextDocumentIdentifier
  ): List[BuildTargetIdentifier] =
    sourceTargets.get(td).getOrElse(Nil)

object TargetSourceMap:
  def empty: TargetSourceMap = TargetSourceMap(Map.empty)
