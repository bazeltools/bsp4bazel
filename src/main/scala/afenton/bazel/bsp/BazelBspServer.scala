package afenton.bazel.bsp

import cats.effect.IO
import cats.effect.std.Queue
import afenton.bazel.bsp.jrpc.Message
import java.nio.file.Path
import java.nio.file.Paths
import afenton.bazel.bsp.runner.MyBazelRunner

class BazelBspServer(client: BspClient, logger: Logger)
    extends BspServer(client):

  private val version = "0.1"
  private val bspVersion = "2.1.0"

  private val bazelRunner = MyBazelRunner(Paths.get("/vol/src/bazel-bsp"))

  def buildInitialize(
      params: InitializeBuildParams
  ): IO[InitializeBuildResult] =
    for
      _ <- logger.info("buildInitialize")
      resp <- IO.pure {
        val compileProvider = CompileProvider(List("scala"))

        InitializeBuildResult(
          "Bazel",
          version,
          bspVersion,
          BuildServerCapabilities(
            compileProvider = Some(compileProvider)
          )
        )
      }
    yield resp

  def buildInitialized(params: Unit): IO[Unit] =
    for _ <- logger.info("build/initialized")
    yield ()

  def workspaceBuildTargets(params: Unit): IO[WorkspaceBuildTargetsResult] =
    for
      _ <- logger.info("workspace/buildTargets")
      resp = WorkspaceBuildTargetsResult(
        List(
          BuildTarget(
            BuildTargetIdentifier("/blerk"),
            Some("my test target"),
            Some("/vol/src/bazel-bsp/example"),
            Nil,
            BuildTargetCapabilities(true, false, false, false),
            List("scala"),
            Nil,
            None,
            None
          )
        )
      )
    yield resp

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

  def buildTargetCompile(params: CompileParams): IO[Unit] =
    bazelRunner.compile(params.targets.head).as(())

  def buildShutdown(params: Unit): IO[Unit] =
    for _ <- logger.info("build/shutdown")
    yield ()

  def buildExit(params: Unit): IO[Unit] =
    for
      _ <- logger.info("build/exit")
      _ <- IO.canceled
    yield ()

  def buildTargetSource(params: SourcesParams): IO[SourcesResult] =
    for
      _ <- logger.info("buildTarget/sources")
      resp = SourcesResult(
        List(
          SourcesItem(
            BuildTargetIdentifier("/blerk"),
            List(
              SourceItem(
                "file:///vol/src/bazel-bsp/example/src/",
                SourceItemKind.Directory,
                false
              )
            ),
            None
            // Some(List("file:///vol/src/bazep-bsp/example/"))
          )
        )
      )
    yield resp

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

end BazelBspServer

class MyBspClient(stdOutQ: Queue[IO, Message], logger: Logger)
    extends BspClient:

  def buildTaskStart(params: TaskStartParams): IO[Unit] =
    for _ <- logger.info("buildTaskStart")
      // _ <- stdOutQ.offer(params)
    yield ()
