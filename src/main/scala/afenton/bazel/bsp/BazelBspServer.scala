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

class BazelBspServer(
    client: BspClient,
    logger: Logger,
    stateRef: Ref[IO, BazelBspServer.ServerState]
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
          bazelRunner = Some(BazelRunner.default(workspaceRoot, bazelWrapper, logger))
        )
      )
      resp <- IO.pure {
        val compileProvider = CompileProvider(List("scala"))

        InitializeBuildResult(
          "Bazel",
          BuildMetaData.Version,
          BuildMetaData.BspVersion,
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
      root <- IOLifts.fromOption(state.workspaceRoot)
      runner <- IOLifts.fromOption(state.bazelRunner)
      ws <- workspaceBuildTargets(())
      ts <- doBuildTargetSources(
        root,
        runner,
        ws.targets.map(_.id)
      )
      _ <- stateRef.update(s => s.copy(targetSourceMap = BazelBspServer.TargetSourceMap(ts)))
    yield ()

  def buildTargetInverseSources(
      params: InverseSourcesParams
  ): IO[InverseSourcesResult] =
    for
      _ <- logger.info("buildTarget/inverseSources")
      state <- stateRef.get
      root <- IOLifts.fromOption(state.workspaceRoot)
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

  def workspaceBuildTargets(params: Unit): IO[WorkspaceBuildTargetsResult] =
    for
      _ <- logger.info("workspace/buildTargets")
      state <- stateRef.get
      runner <- IOLifts.fromOption(state.bazelRunner)
      root <- IOLifts.fromOption(state.workspaceRoot)
      bspTargets <- runner.bspTargets
    yield WorkspaceBuildTargetsResult(
      bspTargets.map(t => buildTarget(t, root))
    )

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
      bazelRunner: BazelRunner,
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
            IOLifts.fromOption(PublishDiagnosticsParams.fromScalacDiagnostic(
              workspaceRoot,
              target,
              fd
            ))
              .flatMap(client.publishDiagnostics(_))
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
      root <- IOLifts.fromOption(state.workspaceRoot)
      pathSet = fds.map(fs => fs.path).toSet
      clearDiagnostics <- state.currentErrors
        .filterNot(fd => pathSet.contains(fd.path))
        .map(_.clearDiagnostics)
        .traverse(fd =>
          IOLifts.fromOption(
            PublishDiagnosticsParams
              .fromScalacDiagnostic(root, target, fd))
        )
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
      runner <- IOLifts.fromOption(state.bazelRunner)
      root <- IOLifts.fromOption(state.workspaceRoot)
      fds <- doCompile(root, runner, target, id)
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
    for
      _ <- logger.info("build/shutdown")
      // _ <- IO.raiseError(new Exception("SHUTTING DOWN"))
    yield ()

  def buildExit(params: Unit): IO[Unit] =
    for
      _ <- logger.info("build/exit")
      _ <- IO.canceled
    yield ()

  private def doBuildTargetSources(
      workspaceRoot: Path,
      bazelRunner: BazelRunner,
      targets: List[BuildTargetIdentifier]
  ): IO[Map[BuildTargetIdentifier, List[TextDocumentIdentifier]]] =
    targets
      .traverse { bt =>
        BazelLabel.fromBuildTargetIdentifier(bt) match
          case Right(bazelTarget) =>
            bazelRunner
              .targetSources(bazelTarget)
              .map(ss => (bt, ss.map(s => TextDocumentIdentifier.file(s))))
          case Left(err) =>
            IO.raiseError(err)
      }
      .map(_.toMap)

  def buildTargetSource(params: SourcesParams): IO[SourcesResult] =
    for
      _ <- logger.info("buildTarget/sources")
      state <- stateRef.get
      root <- IOLifts.fromOption(state.workspaceRoot)
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

  case class ServerState(
      targetSourceMap: BazelBspServer.TargetSourceMap,
      currentErrors: List[FileDiagnostics],
      workspaceRoot: Option[Path],
      bazelRunner: Option[BazelRunner],
      targets: List[BuildTarget]
  )

  def defaultState: ServerState =
    ServerState(BazelBspServer.TargetSourceMap.empty, Nil, None, None, Nil)

  def create(client: BspClient, logger: Logger): IO[BazelBspServer] =
    Ref.of[IO, BazelBspServer.ServerState](defaultState)
      .map { stateRef =>
        BazelBspServer(client, logger, stateRef)
      }

  protected case class TargetSourceMap(
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
