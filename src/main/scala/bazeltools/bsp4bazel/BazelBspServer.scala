package bazeltools.bsp4bazel

import bazeltools.bsp4bazel.jrpc.JRpcClient
import bazeltools.bsp4bazel.jrpc.Message
import bazeltools.bsp4bazel.jrpc.Notification
import bazeltools.bsp4bazel.protocol.*
import bazeltools.bsp4bazel.runner.BazelLabel
import bazeltools.bsp4bazel.runner.BazelRunner
import bazeltools.bsp4bazel.runner.BspBazelRunner
import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Queue
import cats.syntax.all._
import io.bazel.rules_scala.diagnostics.diagnostics.FileDiagnostics
import io.circe.Decoder
import io.circe.DecodingFailure
import io.circe.Json
import io.circe.syntax._

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import cats.effect.kernel.Deferred

import IOLifts.{asIO, mapToIO}

import bazeltools.bsp4bazel.IOLifts
import bazeltools.bsp4bazel.Logger

class Bsp4BazelServer(
    client: BspClient,
    logger: Logger,
    stateRef: Ref[IO, Bsp4BazelServer.ServerState],
    val exitSignal: Deferred[IO, Either[Throwable, Unit]]
) extends BspServer(client):

  def buildInitialize(
      params: InitializeBuildParams
  ): IO[InitializeBuildResult] =
    for
      _ <- logger.info("buildInitialize")
      workspaceRoot = Paths.get(params.rootUri)
      _ <- stateRef.update(state =>
        state.copy(
          workspaceRoot = Some(workspaceRoot),
          bspBazelRunner = Some(
            BspBazelRunner.default(
              workspaceRoot,
              logger
            )
          )
        )
      )
      compileProvider = CompileProvider(List("scala"))
    yield InitializeBuildResult(
          "Bazel",
          BuildInfo.version,
          BuildInfo.bspVersion,
          BuildServerCapabilities(
            compileProvider = Some(compileProvider),
            inverseSourcesProvider = Some(true),
            canReload = Some(true)
          ))

  def buildInitialized(params: Unit): IO[Unit] =
    for
      _ <- logger.info("build/initialized")
      state <- stateRef.get
      root <- state.workspaceRoot.asIO
      ws <- workspaceBuildTargets(())
      ts <- state.bspBazelRunner.mapToIO { runner =>
        doBuildTargetSources(
          root,
          runner,
          ws.targets.map(_.id)
        )
      }
      _ <- stateRef.update(s =>
        s.copy(targetSourceMap = Bsp4BazelServer.TargetSourceMap(ts))
      )
    yield ()

  def buildTargetInverseSources(
      params: InverseSourcesParams
  ): IO[InverseSourcesResult] =
    for
      _ <- logger.info("buildTarget/inverseSources")
      state <- stateRef.get
      root <- state.workspaceRoot.asIO
    yield
      val relativeUri =
        UriFactory.fileUri(
          root.relativize(Paths.get(params.textDocument.uri))
        )

      InverseSourcesResult(
        state.targetSourceMap.targetsForSource(
          TextDocumentIdentifier(relativeUri)
        )
      )

  private def buildTarget(
      bspTarget: BspServer.Target,
      workspaceRoot: Path
  ): BuildTarget =
    BuildTarget(
      BuildTargetIdentifier(bspTarget.id),
      Some(bspTarget.name),
      Some(UriFactory.fileUri(workspaceRoot)),
      List("library"),
      BuildTargetCapabilities(true, false, false, false),
      List("scala"),
      Nil,
      Some("scala"),
      Some(
        ScalaBuildTarget(
          "org.scala-lang",
          "2.12",
          "2.12",
          ScalaPlatform.JVM,
          Nil,
          None
        ).asJson
      )
    )

  def workspaceBuildTargets(params: Unit): IO[WorkspaceBuildTargetsResult] =
    for
      _ <- logger.info("workspace/buildTargets")
      state <- stateRef.get
      runner <- state.bspBazelRunner.asIO
      root <- state.workspaceRoot.asIO
      bspTargets <- runner.bspTargets
    yield WorkspaceBuildTargetsResult(
      bspTargets.map(t => buildTarget(t, root))
    )

  def buildTargetScalacOptions(
      params: ScalacOptionsParams
  ): IO[ScalacOptionsResult] =
    for
      _ <- logger.info("buildTarget/scalacOptions")
    yield ScalacOptionsResult(Nil)

  def buildTargetJavacOptions(
      params: JavacOptionsParams
  ): IO[JavacOptionsResult] =
    for
      _ <- logger.info("buildTarget/javacOptions")
    yield JavacOptionsResult(Nil)

  private def doCompile(
      workspaceRoot: Path,
      bazelRunner: BspBazelRunner,
      target: BuildTargetIdentifier,
      id: TaskId
  ): IO[List[FileDiagnostics]] =
    BazelLabel.fromBuildTargetIdentifier(target) match {
      case Right(bazelLabel) =>
        bazelRunner
          .compile(bazelLabel)
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
            PublishDiagnosticsParams
              .fromScalacDiagnostic(
                workspaceRoot,
                target,
                fd
              )
              .mapToIO(client.publishDiagnostics(_))
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
      root <- state.workspaceRoot.asIO
      pathSet = fds.map(fs => fs.path).toSet
      clearDiagnostics <- state.currentErrors
        .filterNot(fd => pathSet.contains(fd.path))
        .map(_.clearDiagnostics)
        .traverse { fd =>
          PublishDiagnosticsParams
            .fromScalacDiagnostic(root, target, fd)
            .asIO
        }
      _ <- clearDiagnostics.traverse_(client.publishDiagnostics)
      _ <- stateRef.update(_.copy(currentErrors = fds))
    yield ()

  private def compileTarget(target: BuildTargetIdentifier): IO[Unit] =
    for
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
      state <- stateRef.get
      runner <- state.bspBazelRunner.asIO
      fds <- state.workspaceRoot.mapToIO { root =>
        doCompile(root, runner, target, id)
      }
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
      _ <- params.targets.traverse_(compileTarget)
    yield ()

  def buildShutdown(params: Unit): IO[Unit] =
    logger.info("build/shutdown")

  def buildExit(params: Unit): IO[Unit] =
    for
      _ <- logger.info("build/exit")
      _ <- exitSignal.complete(Right(()))
    yield ()

  private def doBuildTargetSources(
      workspaceRoot: Path,
      bazelRunner: BspBazelRunner,
      targets: List[BuildTargetIdentifier]
  ): IO[Map[BuildTargetIdentifier, List[TextDocumentIdentifier]]] =
    targets
      .traverse { bt =>
        BazelLabel.fromBuildTargetIdentifier(bt) match
          case Right(bazelTarget) =>
            bazelRunner
              .targetSources(bazelTarget)
              .map(ss => (bt, ss.map(TextDocumentIdentifier.file)))
          case Left(err) =>
            IO.raiseError(err)
      }
      .map(_.toMap)

  def buildTargetSource(params: SourcesParams): IO[SourcesResult] =
    for
      _ <- logger.info("buildTarget/sources")
      state <- stateRef.get
      root <- state.workspaceRoot.asIO
    yield
      val sourcesItem = params.targets.map { target =>
        val sources = state.targetSourceMap
          .sourcesForTarget(target)
          .map(td => SourceItem(td.uri, SourceItemKind.File, false))
        SourcesItem(
          target,
          sources,
          Some(List(UriFactory.fileUri(root).toString))
        )
      }
      SourcesResult(sourcesItem)

  def buildTargetDependencySources(
      params: DependencySourcesParams
  ): IO[DependencySourcesResult] =
    for
      _ <- logger.info("buildTarget/dependencySources")
    yield DependencySourcesResult(Nil)

  def buildTargetScalaMainClasses(
      params: ScalaMainClassesParams
  ): IO[ScalaMainClassesResult] =
    for
      _ <- logger.info("buildTarget/scalaMainClasses")
    yield ScalaMainClassesResult(Nil, None)

  def buildTargetCleanCache(params: CleanCacheParams): IO[CleanCacheResult] =
    for _ <- logger.info("buildTarget/cleanCache")
    yield CleanCacheResult(Some("Cleaned"), true)

  def cancelRequest(params: CancelParams): IO[Unit] =
    logger.info("$/cancelRequest")

end Bsp4BazelServer

object Bsp4BazelServer:

  case class ServerState(
      targetSourceMap: Bsp4BazelServer.TargetSourceMap,
      currentErrors: List[FileDiagnostics],
      workspaceRoot: Option[Path],
      bspBazelRunner: Option[BspBazelRunner],
      targets: List[BuildTarget]
  )

  def defaultState: ServerState =
    ServerState(Bsp4BazelServer.TargetSourceMap.empty, Nil, None, None, Nil)

  def create(client: BspClient, logger: Logger): IO[Bsp4BazelServer] =
    for
      exitSwitch <- Deferred[IO, Either[Throwable, Unit]]
      stateRef <- Ref.of[IO, Bsp4BazelServer.ServerState](defaultState)
    yield Bsp4BazelServer(client, logger, stateRef, exitSwitch)

  protected case class TargetSourceMap(
      val _targetSources: Map[BuildTargetIdentifier, List[
        TextDocumentIdentifier
      ]]
  ):

    private def invertMap[K, V](map: Map[K, List[V]]): Map[V, NonEmptyList[K]] =
      map.toList
        .flatMap((bt, ls) => ls.map(l => (l, bt)))
        .groupMap(_._1)(_._2)
        .map { case (v, ks) =>
          // we know there is at least one K for each V
          (v, NonEmptyList.fromListUnsafe(ks))  
        }

    private val sourceTargets
        : Map[TextDocumentIdentifier, NonEmptyList[BuildTargetIdentifier]] =
      invertMap(_targetSources)

    def sourcesForTarget(
        bt: BuildTargetIdentifier
    ): List[TextDocumentIdentifier] =
      _targetSources.get(bt).getOrElse(Nil)

    def targetsForSource(
        td: TextDocumentIdentifier
    ): List[BuildTargetIdentifier] =
      sourceTargets.get(td) match {
        case Some(nel) => nel.toList
        case None => Nil
      }

  object TargetSourceMap:
    def empty: TargetSourceMap = TargetSourceMap(Map.empty)
