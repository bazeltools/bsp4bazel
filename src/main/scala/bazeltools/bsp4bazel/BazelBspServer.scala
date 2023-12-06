package bazeltools.bsp4bazel

import bazeltools.bsp4bazel.jrpc.JRpcClient
import bazeltools.bsp4bazel.jrpc.Message
import bazeltools.bsp4bazel.jrpc.Notification
import bazeltools.bsp4bazel.protocol.*
import bazeltools.bsp4bazel.runner.BazelLabel
import bazeltools.bsp4bazel.runner.BazelRunner
import bazeltools.bsp4bazel.runner.BspTaskRunner
import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Queue
import cats.syntax.all._
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
    packageRoots: NonEmptyList[BazelLabel],
    val exitSignal: Deferred[IO, Either[Throwable, Unit]]
) extends BspServer(client)
    with Bsp4BazelServer.Helpers:

  def buildInitialize(
      params: InitializeBuildParams
  ): IO[InitializeBuildResult] =
    for
      _ <- logger.info("buildInitialize")
      workspaceRoot = Paths.get(params.rootUri)
      _ <- stateRef.update(state =>
        state.copy(
          workspaceRoot = Some(workspaceRoot),
          bspTaskRunner = Some(
            BspTaskRunner.default(
              workspaceRoot,
              packageRoots,
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
      )
    )

  def buildInitialized(params: Unit): IO[Unit] =
    for
      _ <- logger.info("build/initialized")
      state <- stateRef.get
      workspaceRoot <- state.workspaceRoot.asIO
      runner <- state.bspTaskRunner.asIO
      workspaceInfo <- runner.workspaceInfo
      targets <- buildTargets(logger, workspaceRoot, runner)
      _ <- stateRef.update(s =>
        s.copy(
          targets = Some(targets),
          targetSourceMap = Bsp4BazelServer.TargetSourceMap(workspaceRoot, targets),
          workspaceInfo = Some(workspaceInfo)
        )
      )
    yield ()

  def workspaceBuildTargets(params: Unit): IO[WorkspaceBuildTargetsResult] =
    for
      _ <- logger.info("workspace/buildTargets")
      state <- stateRef.get
      workspaceInfo <- state.workspaceInfo.asIO
      bspTargets <- state.targets.asIO
    yield WorkspaceBuildTargetsResult(
      bspTargets.map(_.asBuildTarget(workspaceInfo))
    )

  def buildTargetInverseSources(
      params: InverseSourcesParams
  ): IO[InverseSourcesResult] =
    for
      _ <- logger.info("buildTarget/inverseSources")
      state <- stateRef.get
      workspaceRoot <- state.workspaceRoot.asIO
    yield InverseSourcesResult(
      state.targetSourceMap.targetsForSource(
        params.textDocument.relativize(workspaceRoot)
      )
    )

  def buildTargetScalacOptions(
      params: ScalacOptionsParams
  ): IO[ScalacOptionsResult] =
    for
      _ <- logger.info("buildTarget/scalacOptions")
      state <- stateRef.get
      workspaceInfo <- state.workspaceInfo.asIO
      bspTargets <- state.targets.asIO
    yield
      val select = params.targets.toSet
      val filtered = bspTargets.filter(bi => select(bi.id))
      require(
        filtered.size == params.targets.size,
        "Some of the requested targets didn't exist. Shouldn't be possible"
      )
      ScalacOptionsResult(filtered.map(_.asScalaOptionItem(workspaceInfo)))

  private def buildTargetScalacOption(
      target: BuildTargetIdentifier
  ): IO[ScalacOptionsItem] =
    for
      state <- stateRef.get
      bspTargets <- state.targets.asIO
    yield ScalacOptionsItem(
      target = target,
      options = Nil,
      classpath = Nil,
      classDirectory = UriFactory.fileUri(Paths.get(".bsp/semanticdb"))
    )

  def buildTargetJavacOptions(
      params: JavacOptionsParams
  ): IO[JavacOptionsResult] =
    for _ <- logger.info("buildTarget/javacOptions")
    yield JavacOptionsResult(Nil)

  def buildTargetCompile(params: CompileParams): IO[Unit] =
    params.targets.traverse_(buildSingleTarget)

  def buildSingleTarget(target: BuildTargetIdentifier): IO[Unit] =
    for
      _ <- logger.info(s"Compiling target: ${target.uri.getPath}")
      state <- stateRef.get
      workspaceRoot <- state.workspaceRoot.asIO
      runner <- state.bspTaskRunner.asIO
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
      newErrors <- compile(workspaceRoot, runner, target, id, logger)
      _ <- newErrors.traverse_(client.publishDiagnostics)
      clearDiagnostics = mkClearDiagnostics(
        target,
        workspaceRoot,
        newErrors,
        state.currentErrors
      )
      _ <- clearDiagnostics.traverse_(client.publishDiagnostics)
      _ <- stateRef.update(s => s.copy(currentErrors = newErrors))
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

  def buildTargetSources(params: SourcesParams): IO[SourcesResult] =
    for
      _ <- logger.info("buildTarget/sources")
      state <- stateRef.get
      workspaceRoot <- state.workspaceRoot.asIO
    yield
      val sourcesItem = params.targets.map { target =>
        val sources = state.targetSourceMap
          .sourcesForTarget(target)
          .map(td => SourceItem(td.uri, SourceItemKind.File, false))
        SourcesItem(
          target,
          sources,
          None
          // Some(List(UriFactory.fileUri(workspaceRoot).toString))
        )
      }
      SourcesResult(sourcesItem)

  def buildTargetDependencySources(
      params: DependencySourcesParams
  ): IO[DependencySourcesResult] =
    for _ <- logger.info("buildTarget/dependencySources")
    yield DependencySourcesResult(Nil)

  def buildTargetScalaMainClasses(
      params: ScalaMainClassesParams
  ): IO[ScalaMainClassesResult] =
    for _ <- logger.info("buildTarget/scalaMainClasses")
    yield ScalaMainClassesResult(Nil, None)

  def buildTargetCleanCache(params: CleanCacheParams): IO[CleanCacheResult] =
    for _ <- logger.info("buildTarget/cleanCache")
    yield CleanCacheResult(Some("Cleaned"), true)

  def cancelRequest(params: CancelParams): IO[Unit] =
    logger.info("$/cancelRequest")

  def buildShutdown(params: Unit): IO[Unit] =
    logger.info("build/shutdown")

  def buildExit(params: Unit): IO[Unit] =
    for
      _ <- logger.info("build/exit")
      _ <- exitSignal.complete(Right(()))
    yield ()

end Bsp4BazelServer

object Bsp4BazelServer:

  protected trait Helpers:
    this: Bsp4BazelServer =>

    def buildTargets(
        logger: Logger,
        workspaceRoot: Path,
        runner: BspTaskRunner
    ): IO[List[BspTaskRunner.BspTarget]] =
      for
        _ <- logger.info(s"Fetching build targets")
        ids <- runner.buildTargets
        infos <- runner.bspTargets(ids)
      yield infos

    // If we don't get back any FileDiagnostics, we assume there's now no errors, so have to
    // clear these out by publishing an empty Diagnostic for them
    def mkClearDiagnostics(
        target: BuildTargetIdentifier,
        workspaceRoot: Path,
        newErrors: List[PublishDiagnosticsParams],
        currentErrors: List[PublishDiagnosticsParams]
    ): List[PublishDiagnosticsParams] =
      val select = newErrors.map(_.textDocument).toSet
      currentErrors
        // Filter out any errors that are still present
        .filterNot(pd => select(pd.textDocument))
        // Create an empty diagnostic for the ones left
        .map(_.clearDiagnostics)

    def compile(
        workspaceRoot: Path,
        bazelRunner: BspTaskRunner,
        target: BuildTargetIdentifier,
        id: TaskId,
        logger: Logger
    ): IO[List[PublishDiagnosticsParams]] =
      BazelLabel.fromBuildTargetIdentifier(target) match {
        case Right(bazelLabel) =>
          bazelRunner
            .compile(bazelLabel)
            .filterNot(fd => fd.path.toString.endsWith("<no file>"))
            .evalTap(fd => logger.info(s"Got diagnostic: $fd"))
            .map { fd =>
              PublishDiagnosticsParams
                .fromScalacDiagnostic(
                  workspaceRoot,
                  target,
                  fd
                )
                .getOrElse(
                  throw new Exception(
                    s"Couldn't convert diagnostic into something publishable. Got $fd"
                  )
                )
            }
            .compile
            .toList

        case Left(err) => IO.raiseError(err)
      }

  end Helpers

  protected case class ServerState(
      targetSourceMap: Bsp4BazelServer.TargetSourceMap,
      currentErrors: List[PublishDiagnosticsParams],
      workspaceRoot: Option[Path],
      bspTaskRunner: Option[BspTaskRunner],
      targets: Option[List[BspTaskRunner.BspTarget]],
      workspaceInfo: Option[BspTaskRunner.WorkspaceInfo]
  )

  def defaultState: ServerState =
    ServerState(
      Bsp4BazelServer.TargetSourceMap.empty,
      Nil,
      None,
      None,
      None,
      None
    )

  def create(
      client: BspClient,
      logger: Logger,
      packageRoots: NonEmptyList[BazelLabel]
  ): IO[Bsp4BazelServer] =
    for
      exitSwitch <- Deferred[IO, Either[Throwable, Unit]]
      stateRef <- Ref.of[IO, Bsp4BazelServer.ServerState](defaultState)
    yield Bsp4BazelServer(client, logger, stateRef, packageRoots, exitSwitch)

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
        case None      => Nil
      }

  object TargetSourceMap:
    def apply(
        workspaceRoot: Path,
        targets: List[BspTaskRunner.BspTarget]
    ): TargetSourceMap =
      TargetSourceMap(
        targets
          .map(t =>
            (
              t.id,
              t.info.srcs.map(path =>
                TextDocumentIdentifier.file(workspaceRoot.resolve(path))
              )
            )
          )
          .toMap
      )

    def empty: TargetSourceMap = TargetSourceMap(Map.empty)
