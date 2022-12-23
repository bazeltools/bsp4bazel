package afenton.bazel.bsp

import cats.effect.IO

trait BspServerClient:

  def sendMessage[A: Encoder, B: Decoder](method: String, params: A): IO[B] =
    val json = Encoder[A].apply(params)
    sendNotification(Notification("2.0", method, Some(json)))

  def sendMessage(n: Message): IO[Unit] =
    for
      _ <- logger.info(n.method)
      _ <- stdOutQ.offer(n)
    yield ()

  def buildInitialize(params: InitializeBuildParams): IO[InitializeBuildResult]
  def buildInitialized(params: Unit): IO[Unit]
  def buildShutdown(params: Unit): IO[Unit]
  def buildExit(params: Unit): IO[Unit]
  def workspaceBuildTargets(params: Unit): IO[WorkspaceBuildTargetsResult]
  def buildTargetCompile(params: CompileParams): IO[Unit]
  def buildTargetScalacOptions(
      params: ScalacOptionsParams
  ): IO[ScalacOptionsResult]
  def buildTargetJavacOptions(
      params: JavacOptionsParams
  ): IO[JavacOptionsResult]
  def buildTargetSource(params: SourcesParams): IO[SourcesResult]
  def buildTargetDependencySources(
      params: DependencySourcesParams
  ): IO[DependencySourcesResult]
  def buildTargetScalaMainClasses(
      params: ScalaMainClassesParams
  ): IO[ScalaMainClassesResult]
  def buildTargetInverseSources(
      params: InverseSourcesParams
  ): IO[InverseSourcesResult]
  def buildTargetCleanCache(params: CleanCacheParams): IO[CleanCacheResult]
  def cancelRequest(params: CancelParams): IO[Unit]

/** Simulates client requests. Useful for testing
  */
trait ClientSimulator:
  def start(): IO[Unit]
