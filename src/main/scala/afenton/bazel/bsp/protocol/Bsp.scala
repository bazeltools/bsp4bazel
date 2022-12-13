package afenton.bazel.bsp

import afenton.bazel.bsp.jrpc.RpcFunction
import cats.effect.IO
import io.circe.*
import io.circe.generic.semiauto.*
import io.circe.syntax._

case class BuildTargetIdentifier(uri: String)
object BuildTargetIdentifier:
  given Codec[BuildTargetIdentifier] =
    deriveCodec[BuildTargetIdentifier]

case class BuildTarget(
    id: BuildTargetIdentifier,
    displayName: Option[String],
    baseDirectory: Option[String],
    tags: List[String],
    capabilities: BuildTargetCapabilities,
    languageIds: List[String],
    dependencies: List[BuildTargetIdentifier],
    dataKind: Option[String],
    data: Option[Json]
)
object BuildTarget:
  given Encoder[BuildTarget] =
    deriveEncoder[BuildTarget]

case class BuildTargetCapabilities(
    canCompile: Boolean,
    canTest: Boolean,
    canRun: Boolean,
    canDebug: Boolean
)
object BuildTargetCapabilities:
  given Decoder[BuildTargetCapabilities] =
    deriveDecoder[BuildTargetCapabilities]

case class InitializeBuildParams(
    displayName: String,
    version: String,
    bspVersion: String,
    capabilities: BuildServerCapabilities,
    rootUri: String,
    data: Option[Json]
)
object InitializeBuildParams:
  given Decoder[InitializeBuildParams] =
    deriveDecoder[InitializeBuildParams]

case class BuildClientCapabilities(languageIds: List[String])

case class InitializeBuildResult(
    displayName: String,
    version: String,
    bspVersion: String,
    capabilities: BuildServerCapabilities,
    data: Option[Json] = None
)
object InitializeBuildResult:
  given Encoder[InitializeBuildResult] =
    deriveEncoder[InitializeBuildResult]

case class BuildServerCapabilities(
    compileProvider: Option[CompileProvider] = None,
    testProvider: Option[TestProvider] = None,
    runProvider: Option[RunProvider] = None,
    debugProvider: Option[DebugProvider] = None,
    inverseSourcesProvider: Option[Boolean] = None,
    dependencySourcesProvider: Option[Boolean] = None,
    dependencyModulesProvider: Option[Boolean] = None,
    resourcesProvider: Option[Boolean] = None,
    outputPathsProvider: Option[Boolean] = None,
    buildTargetChangedProvider: Option[Boolean] = None,
    jvmRunEnvironmentProvider: Option[Boolean] = None,
    jvmTestEnvironmentProvider: Option[Boolean] = None,
    canReload: Option[Boolean] = None
)
object BuildServerCapabilities:
  given Encoder[BuildServerCapabilities] =
    deriveEncoder[BuildServerCapabilities]
  given Decoder[BuildServerCapabilities] =
    deriveDecoder[BuildServerCapabilities]

case class CompileProvider(languageIds: List[String])
object CompileProvider:
  given Encoder[CompileProvider] =
    deriveEncoder[CompileProvider]
  given Decoder[CompileProvider] =
    deriveDecoder[CompileProvider]

case class TestProvider(languageIds: List[String])
object TestProvider:
  given Encoder[TestProvider] =
    deriveEncoder[TestProvider]
  given Decoder[TestProvider] =
    deriveDecoder[TestProvider]

case class RunProvider(languageIds: List[String])
object RunProvider:
  given Encoder[RunProvider] =
    deriveEncoder[RunProvider]
  given Decoder[RunProvider] =
    deriveDecoder[RunProvider]

case class DebugProvider(languageIds: List[String])
object DebugProvider:
  given Encoder[DebugProvider] =
    deriveEncoder[DebugProvider]
  given Decoder[DebugProvider] =
    deriveDecoder[DebugProvider]

case class TaskId(id: String, parents: Option[List[String]])
object TaskId:
  given Encoder[TaskId] =
    deriveEncoder[TaskId]
  given Decoder[TaskId] =
    deriveDecoder[TaskId]

case class TaskStartParams(
    taskId: TaskId,
    eventTime: Option[String],
    message: Option[String],
    dataKind: Option[String],
    data: Json
)
object TaskStartParams:
  given Encoder[TaskStartParams] =
    deriveEncoder[TaskStartParams]

case class CompileParams(
    targets: List[BuildTargetIdentifier],
    originId: Option[String],
    arguments: Option[List[String]]
)
object CompileParams:
  given Decoder[CompileParams] = deriveDecoder[CompileParams]

case class WorkspaceBuildTargetsResult(targets: List[BuildTarget])
object WorkspaceBuildTargetsResult:
  given Encoder[WorkspaceBuildTargetsResult] =
    deriveEncoder[WorkspaceBuildTargetsResult]

case class ScalacOptionsParams(targets: List[BuildTargetIdentifier])
object ScalacOptionsParams:
  given Decoder[ScalacOptionsParams] =
    deriveDecoder[ScalacOptionsParams]

case class ScalacOptionsResult(items: List[ScalacOptionsItem])
object ScalacOptionsResult:
  given Encoder[ScalacOptionsResult] =
    deriveEncoder[ScalacOptionsResult]

case class ScalacOptionsItem(
    target: BuildTargetIdentifier,
    options: List[String],
    classpath: List[String],
    classDirectory: String
)
object ScalacOptionsItem:
  given Encoder[ScalacOptionsItem] =
    deriveEncoder[ScalacOptionsItem]

case class JavacOptionsParams(targets: List[BuildTargetIdentifier])
object JavacOptionsParams:
  given Decoder[JavacOptionsParams] =
    deriveDecoder[JavacOptionsParams]

case class JavacOptionsResult(items: List[JavacOptionsItem])
object JavacOptionsResult:
  given Encoder[JavacOptionsResult] =
    deriveEncoder[JavacOptionsResult]

case class JavacOptionsItem(
    target: BuildTargetIdentifier,
    options: List[String],
    classpath: List[String],
    classDirectory: String
)
object JavacOptionsItem:
  given Encoder[JavacOptionsItem] =
    deriveEncoder[JavacOptionsItem]

case class SourcesParams(targets: List[BuildTargetIdentifier])
object SourcesParams:
  given Decoder[SourcesParams] =
    deriveDecoder[SourcesParams]

case class SourcesResult(items: List[SourcesItem])
object SourcesResult:
  given Encoder[SourcesResult] =
    deriveEncoder[SourcesResult]

case class SourcesItem(
    target: BuildTargetIdentifier,
    sources: List[SourceItem],
    roots: Option[List[String]]
)
object SourcesItem:
  given Encoder[SourcesItem] =
    deriveEncoder[SourcesItem]

case class SourceItem(uri: String, kind: SourceItemKind, generated: Boolean)
object SourceItem:
  given Encoder[SourceItem] =
    deriveEncoder[SourceItem]

enum SourceItemKind(val id: Int):
  case File extends SourceItemKind(1)
  case Directory extends SourceItemKind(2)
object SourceItemKind:
  given Encoder[SourceItemKind] =
    Encoder.instance(_.id.asJson)

case class DependencySourcesParams(targets: List[BuildTargetIdentifier])
object DependencySourcesParams:
  given Decoder[DependencySourcesParams] =
    deriveDecoder[DependencySourcesParams]

case class DependencySourcesResult(items: List[DependencySourcesItem])
object DependencySourcesResult:
  given Encoder[DependencySourcesResult] =
    deriveEncoder[DependencySourcesResult]

case class DependencySourcesItem(
    target: BuildTargetIdentifier,
    sources: List[String]
)
object DependencySourcesItem:
  given Encoder[DependencySourcesItem] =
    deriveEncoder[DependencySourcesItem]

case class ScalaMainClassesParams(
    targets: List[BuildTargetIdentifier],
    origin: Option[String]
)
object ScalaMainClassesParams:
  given Decoder[ScalaMainClassesParams] =
    deriveDecoder[ScalaMainClassesParams]

case class ScalaMainClassesResult(
    items: List[ScalaMainClassesItem],
    origin: Option[String]
)
object ScalaMainClassesResult:
  given Encoder[ScalaMainClassesResult] =
    deriveEncoder[ScalaMainClassesResult]

case class ScalaMainClassesItem(
    target: BuildTargetIdentifier,
    classes: List[ScalaMainClass]
)
object ScalaMainClassesItem:
  given Encoder[ScalaMainClassesItem] =
    deriveEncoder[ScalaMainClassesItem]

case class ScalaMainClass(
    `class`: String,
    arguments: List[String],
    jvmOptions: List[String],
    environmentVariables: Option[List[String]]
)
object ScalaMainClass:
  given Encoder[ScalaMainClass] =
    deriveEncoder[ScalaMainClass]

trait BspClient:
  def buildTaskStart(params: TaskStartParams): IO[Unit]

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
  }
