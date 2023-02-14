package bazeltools.bsp4bazel.protocol

import bazeltools.bsp4bazel.jrpc.RpcFunction
import cats.effect.IO

import java.net.URI

trait BspServer(client: BspClient):
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

object BspServer:
  def jsonRpcRouter(
      bspServer: BspServer
  ): PartialFunction[String, RpcFunction[_, _]] = {
    case "build/initialize"  => RpcFunction(bspServer.buildInitialize)
    case "build/initialized" => RpcFunction(bspServer.buildInitialized)
    case "build/shutdown"    => RpcFunction(bspServer.buildShutdown)
    case "build/exit"        => RpcFunction(bspServer.buildExit)
    case "workspace/buildTargets" =>
      RpcFunction(bspServer.workspaceBuildTargets)
    case "buildTarget/scalacOptions" =>
      RpcFunction(bspServer.buildTargetScalacOptions)
    case "buildTarget/javacOptions" =>
      RpcFunction(bspServer.buildTargetJavacOptions)
    case "buildTarget/sources" =>
      RpcFunction(bspServer.buildTargetSource)
    case "buildTarget/dependencySources" =>
      RpcFunction(bspServer.buildTargetDependencySources)
    case "buildTarget/scalaMainClasses" =>
      RpcFunction(bspServer.buildTargetScalaMainClasses)
    case "buildTarget/compile" =>
      RpcFunction(bspServer.buildTargetCompile)
    case "buildTarget/inverseSources" =>
      RpcFunction(bspServer.buildTargetInverseSources)
    case "buildTarget/cleanCache" =>
      RpcFunction(bspServer.buildTargetCleanCache)
    case "$/cancelRequest" =>
      RpcFunction(bspServer.cancelRequest)
  }

  case class Target(id: URI, name: String)
