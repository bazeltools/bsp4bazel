package afenton.bazel.bsp

import afenton.bazel.bsp.jrpc.JRpcClient
import afenton.bazel.bsp.jrpc.Message
import afenton.bazel.bsp.jrpc.Notification
import afenton.bazel.bsp.protocol.*
import afenton.bazel.bsp.runner.BazelLabel
import afenton.bazel.bsp.runner.BazelRunner
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

class BazelBspServer(
    client: BspClient,
    logger: Logger,
    stateRef: Ref[IO, BazelBspServer.ServerState],
    val exitSignal: Deferred[IO, Either[Throwable, Unit]]
) extends BspServer(client):

  def buildInitialize(
      params: InitializeBuildParams
  ): IO[InitializeBuildResult] =
    for
      _ <- logger.info("buildInitialize")
      workspaceRoot = Paths.get(params.rootUri)
      bazelWrapper <- BazelRunner.BazelWrapper.default(workspaceRoot)
      _ <- stateRef.update(state =>
        state.copy(
          workspaceRoot = Some(workspaceRoot),
          bazelRunner =
            Some(BazelRunner.default(workspaceRoot, bazelWrapper, logger))
        )
      )
      resp <- IO.pure {
        val compileProvider = CompileProvider(List("scala"))

        InitializeBuildResult(
          "Bazel",
          BuildInfo.version,
          BuildInfo.bspVersion,
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
      root <- state.workspaceRoot.asIO
      runner <- state.bazelRunner.asIO
      bspTargetLabels <- runner.bspTargets
      details <- targetDetails(root, runner, bspTargetLabels)
      _ <- stateRef.update(s =>
        s.copy(
          targets = details.keys.toList,
          targetDetails = details,
          targetSourceMap = BazelBspServer.TargetSourceMap.fromTargetDetails(
            details.values.toList
          )
        )
      )
    yield ()

  private def targetDetails(
      workspaceRoot: Path,
      bazelRunner: BazelRunner,
      targetLabels: List[BazelLabel]
  ): IO[Map[BuildTargetIdentifier, BazelBspServer.TargetDetails]] =
    targetLabels
      .traverse { label =>
        for
          config <- bazelRunner.bspConfig(label)
          bt = mkBuildTarget(workspaceRoot, label, config)
        yield (bt.id, BazelBspServer.TargetDetails(bt, config, label))
      }
      .map(_.toMap)

  private def mkBuildTarget(
      workspaceRoot: Path,
      targetLabel: BazelLabel,
      bspConfig: BazelRunner.BspConfig
  ): BuildTarget =
    val id = UriFactory.bazelUri(targetLabel.asString)
    val name =
      targetLabel.target.map(_.asString).getOrElse(targetLabel.asString)

    BuildTarget(
      BuildTargetIdentifier(id),
      Some(name),
      Some(UriFactory.fileUri(workspaceRoot)),
      List("library"),
      BuildTargetCapabilities(true, false, false, false),
      List("scala"),
      Nil,
      Some("scala"),
      Some(
        ScalaBuildTarget(
          "org.scala-lang",
          bspConfig.scalaVersion,
          bspConfig.majorScalaVersion,
          ScalaPlatform.JVM,
          bspConfig.compileJars.map(p => UriFactory.fileUri(workspaceRoot.resolve(p))),
          None
        ).asJson
      )
    )

  def workspaceBuildTargets(params: Unit): IO[WorkspaceBuildTargetsResult] =
    for
      _ <- logger.info("workspace/buildTargets")
      state <- stateRef.get
    yield WorkspaceBuildTargetsResult(
      state.targetDetails.values.toList.map(_.buildTarget)
    )

  def buildTargetScalacOptions(
      params: ScalacOptionsParams
  ): IO[ScalacOptionsResult] =
    for
      _ <- logger.info("buildTarget/scalacOptions")
      state <- stateRef.get
      root <- state.workspaceRoot.asIO
    yield
      val items = params.targets.map { target =>
        val details = state.targetDetails(target)
        val semanticDbPath = root.resolve(".bsp/.semanticdb")
        ScalacOptionsItem(
          target,
          details.bspConfig.scalacOptions ++ List(
            "-Xplugin:/vol/src/bsp4bazel/examples/simple-no-errors/bazel-out/k8-fastbuild/bin/external/org_scalameta_semanticdb_scalac/org_scalameta_semanticdb_scalac.stamp/semanticdb-scalac_2.12.14-4.7.3-stamped.jar",
            "-P:semanticdb:sourceroot:/vol/src/bsp4bazel/examples/simple-no-errors",
            "-P:semanticdb:targetroot:/vol/src/bsp4bazel/examples/simple-no-errors/.bsp/.semanticdb"
          ),
          Nil,
          UriFactory.fileUri(semanticDbPath)
        )
      }
      ScalacOptionsResult(items)

  def buildTargetJavacOptions(
      params: JavacOptionsParams
  ): IO[JavacOptionsResult] =
    for
      _ <- logger.info("buildTarget/javacOptions")
      resp = JavacOptionsResult(Nil)
    yield resp

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

  private def doCompile(
      workspaceRoot: Path,
      bazelRunner: BazelRunner,
      target: BuildTargetIdentifier,
      compileTarget: BazelLabel,
      id: TaskId
  ): IO[List[FileDiagnostics]] =
    bazelRunner
      .compile(compileTarget)
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
      state <- stateRef.get
      _ <- cats.effect.std.Console[IO].error(state.targetDetails)
      details <- state.targetDetails.get(target).asIO
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
      runner <- state.bazelRunner.asIO
      fds <- state.workspaceRoot.mapToIO { root =>
        doCompile(root, runner, target, details.bspConfig.compileLabel, id)
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
    for _ <- logger.info("build/shutdown")
    yield ()

  def buildExit(params: Unit): IO[Unit] =
    for
      _ <- logger.info("build/exit")
      _ <- exitSignal.complete(Right(()))
    yield ()

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

object BazelBspServer:

  case class TargetDetails(
      buildTarget: BuildTarget,
      bspConfig: BazelRunner.BspConfig,
      label: BazelLabel
  )

  case class ServerState(
      workspaceRoot: Option[Path],
      bazelRunner: Option[BazelRunner],
      targets: List[BuildTargetIdentifier],
      targetDetails: Map[BuildTargetIdentifier, TargetDetails],
      targetSourceMap: BazelBspServer.TargetSourceMap,
      currentErrors: List[FileDiagnostics]
  )

  def defaultState: ServerState =
    ServerState(
      None,
      None,
      Nil,
      Map.empty,
      BazelBspServer.TargetSourceMap.empty,
      Nil
    )

  def create(client: BspClient, logger: Logger): IO[BazelBspServer] =
    for
      exitSwitch <- Deferred[IO, Either[Throwable, Unit]]
      stateRef <- Ref.of[IO, BazelBspServer.ServerState](defaultState)
    yield BazelBspServer(client, logger, stateRef, exitSwitch)

  protected case class TargetSourceMap(
      val _targetSources: Map[BuildTargetIdentifier, List[
        TextDocumentIdentifier
      ]]
  ):

    private def invertMap[K, V](map: Map[K, List[V]]): Map[V, List[K]] =
      map.toList
        .flatMap((bt, ls) => ls.map(l => (l, bt)))
        .groupMap(_._1)(_._2)

    private lazy val sourceTargets
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

    def fromTargetDetails(targetDetails: List[TargetDetails]): TargetSourceMap =
      val tsm = targetDetails.map { td =>
        val files =
          td.bspConfig.sources.map(s => TextDocumentIdentifier.file(s))
        (td.buildTarget.id, files)
      }.toMap

      TargetSourceMap(tsm)
