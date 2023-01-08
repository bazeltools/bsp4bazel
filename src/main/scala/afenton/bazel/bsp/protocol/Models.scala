package afenton.bazel.bsp.protocol

import afenton.bazel.bsp.jrpc.Notification
import afenton.bazel.bsp.jrpc.RpcFunction
import afenton.bazel.bsp.jrpc.decodeIntOrString
import afenton.bazel.bsp.jrpc.encodeIntOrString
import afenton.bazel.bsp.runner.BazelLabel
import cats.effect.IO
import cats.syntax.all._
import io.bazel.rules_scala.diagnostics.diagnostics.FileDiagnostics as ScalacDiagnostic
import io.bazel.rules_scala.diagnostics.diagnostics.Position as ScalacPosition
import io.bazel.rules_scala.diagnostics.diagnostics.Range as ScalacRange
import io.bazel.rules_scala.diagnostics.diagnostics.Severity as ScalacSeverity
import io.circe.*
import io.circe.generic.semiauto.*
import io.circe.syntax._

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

object UriFactory:
  def fileUri(path: Path): URI =
    if path.startsWith("/") then new URI(s"file://$path")
    else new URI(s"file:///$path")

  def fileUri(path: String): URI =
    fileUri(Paths.get(path))

  def bazelUri(label: String): URI =
    require(
      BazelLabel.fromString(label).isRight,
      s"Invalid Bazel label: $label"
    )
    new URI(s"bazel://${label}")

case class BuildTargetIdentifier(uri: URI)
object BuildTargetIdentifier:
  given Codec[BuildTargetIdentifier] =
    deriveCodec[BuildTargetIdentifier]

  def bazel(uri: String): BuildTargetIdentifier =
    BuildTargetIdentifier(UriFactory.bazelUri(uri))

  def file(uri: String): BuildTargetIdentifier =
    BuildTargetIdentifier(UriFactory.fileUri(uri))

case class ScalaBuilderTarget(
    scalaOrganization: String,
    scalaVersion: String,
    scalaBinaryVersion: String,
    platform: ScalaPlatform,
    jars: List[String],
    jvmBuildTarget: Option[JvmBuildTarget]
)
object ScalaBuilderTarget:
  given Encoder[ScalaBuilderTarget] =
    deriveEncoder[ScalaBuilderTarget]

enum ScalaPlatform(val id: Int):
  case JVM extends ScalaPlatform(1)
  case JS extends ScalaPlatform(2)
  case Native extends ScalaPlatform(3)
object ScalaPlatform:
  given Encoder[ScalaPlatform] = Encoder.instance(sp => sp.id.asJson)

case class JvmBuildTarget(javaHome: Option[URI], javaVersion: Option[String])
object JvmBuildTarget:
  given Encoder[JvmBuildTarget] =
    deriveEncoder[JvmBuildTarget]

case class BuildTarget(
    id: BuildTargetIdentifier,
    displayName: Option[String],
    baseDirectory: Option[URI],
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
    capabilities: BuildClientCapabilities,
    rootUri: URI,
    data: Option[Json]
)
object InitializeBuildParams:
  given Codec[InitializeBuildParams] =
    deriveCodec[InitializeBuildParams]

case class BuildClientCapabilities(languageIds: List[String])

case class InitializeBuildResult(
    displayName: String,
    version: String,
    bspVersion: String,
    capabilities: BuildServerCapabilities,
    data: Option[Json] = None
)
object InitializeBuildResult:
  given Codec[InitializeBuildResult] =
    deriveCodec[InitializeBuildResult]

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
  given Codec[TaskId] =
    deriveCodec[TaskId]

case class TaskStartParams(
    taskId: TaskId,
    eventTime: Option[Long],
    message: Option[String],
    dataKind: Option[TaskDataKind],
    data: Option[Json]
)
object TaskStartParams:
  given Codec[TaskStartParams] =
    deriveCodec[TaskStartParams]

case class TaskProgressParams(
    taskId: TaskId,
    eventTime: Option[Long],
    message: Option[String],
    progress: Option[Long],
    units: Option[String],
    dataKind: Option[String],
    data: Option[Json]
)
object TaskProgressParams:
  given Encoder[TaskProgressParams] =
    deriveEncoder[TaskProgressParams]

enum StatusCode(val id: Int):
  case Ok extends StatusCode(1)
  case Error extends StatusCode(2)
  case Cancelled extends StatusCode(3)
object StatusCode:
  given Encoder[StatusCode] =
    Encoder.instance(_.id.asJson)
  given Decoder[StatusCode] =
    Decoder[Int].map {
      case 1 => StatusCode.Ok
      case 2 => StatusCode.Error
      case 3 => StatusCode.Cancelled
    }

case class TaskFinishParams(
    taskId: TaskId,
    eventTime: Option[Long],
    message: Option[String],
    status: StatusCode,
    dataKind: Option[TaskDataKind],
    data: Option[Json]
)
object TaskFinishParams:
  given Codec[TaskFinishParams] =
    deriveCodec[TaskFinishParams]

case class CompileParams(
    targets: List[BuildTargetIdentifier],
    originId: Option[String],
    arguments: Option[List[String]]
)
object CompileParams:
  given Codec[CompileParams] = deriveCodec[CompileParams]

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

case class SourceItem(uri: URI, kind: SourceItemKind, generated: Boolean)
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

case class PublishDiagnosticsParams(
    textDocument: TextDocumentIdentifier,
    buildTarget: BuildTargetIdentifier,
    originId: Option[String],
    diagnostics: List[Diagnostic],
    reset: Boolean
)
object PublishDiagnosticsParams:
  given Codec[PublishDiagnosticsParams] =
    deriveCodec[PublishDiagnosticsParams]

  def fromScalacDiagnostic(
      baseDir: Path,
      bt: BuildTargetIdentifier,
      fd: ScalacDiagnostic
  ): Option[PublishDiagnosticsParams] =
    fd.diagnostics.toList
      .traverse { scalacD =>
        scalacD.range
          .flatMap(Range.fromScalacRange(_))
          .map {
            Diagnostic(
              _,
              Some(DiagnosticSeverity.fromScalacSeverity(scalacD.severity)),
              Some(1),
              None,
              Some("Scalac"),
              scalacD.message,
              None,
              None,
              None
            )
          }
      }
      .map { diagnostics =>
        val path = fd.path.toString
          .replaceFirst("^workspace-root://", baseDir.toUri.toString)

        PublishDiagnosticsParams(
          TextDocumentIdentifier(new URI(path)),
          bt,
          None,
          diagnostics,
          true
        )
      }

case class TextDocumentIdentifier(uri: URI)

object TextDocumentIdentifier:
  given Codec[TextDocumentIdentifier] =
    deriveCodec[TextDocumentIdentifier]

  def file(file: Path): TextDocumentIdentifier =
    TextDocumentIdentifier(UriFactory.fileUri(file))

  def file(file: String): TextDocumentIdentifier =
    this.file(Paths.get(file))

case class Diagnostic(
    range: Range,
    severity: Option[DiagnosticSeverity],
    code: Option[Int | String],
    codeDescription: Option[CodeDescription],
    source: Option[String],
    message: String,
    tags: Option[List[DiagnosticTag]],
    relatedInformation: Option[List[DiagnosticRelatedInformation]],
    data: Option[Json]
)

object Diagnostic:
  given Codec[Diagnostic] =
    deriveCodec[Diagnostic]

case class DiagnosticRelatedInformation(location: Location, message: String)

object DiagnosticRelatedInformation:
  given Codec[DiagnosticRelatedInformation] =
    deriveCodec[DiagnosticRelatedInformation]

case class Location(uri: URI, range: Range)

object Location:
  given Codec[Location] =
    deriveCodec[Location]

enum DiagnosticTag(val i: Int):
  case Unnecessary extends DiagnosticTag(1)
  case Deprecated extends DiagnosticTag(2)

object DiagnosticTag:

  given Encoder[DiagnosticTag] =
    Encoder.instance(dt => dt.i.asJson)

  given Decoder[DiagnosticTag] =
    Decoder[Int].map {
      case 1 => DiagnosticTag.Unnecessary
      case 2 => DiagnosticTag.Deprecated
    }

enum DiagnosticSeverity(val i: Int):
  case Error extends DiagnosticSeverity(1)
  case Warning extends DiagnosticSeverity(2)
  case Information extends DiagnosticSeverity(3)
  case Hint extends DiagnosticSeverity(4)
object DiagnosticSeverity:

  given Encoder[DiagnosticSeverity] =
    Encoder.instance(ds => ds.i.asJson)

  given Decoder[DiagnosticSeverity] =
    Decoder[Int].map {
      case 1 => DiagnosticSeverity.Error
      case 2 => DiagnosticSeverity.Warning
      case 3 => DiagnosticSeverity.Information
      case 4 => DiagnosticSeverity.Hint
    }

  def fromScalacSeverity(sv: ScalacSeverity): DiagnosticSeverity =
    sv match {
      case ScalacSeverity.ERROR       => DiagnosticSeverity.Error
      case ScalacSeverity.WARNING     => DiagnosticSeverity.Warning
      case ScalacSeverity.INFORMATION => DiagnosticSeverity.Information
      case ScalacSeverity.HINT        => DiagnosticSeverity.Hint
      case _                          => DiagnosticSeverity.Error
    }

case class CodeDescription(href: String)
object CodeDescription:
  given Codec[CodeDescription] =
    deriveCodec[CodeDescription]

case class Range(start: Position, end: Position)
object Range:
  given Codec[Range] =
    deriveCodec[Range]

  def fromScalacRange(rng: ScalacRange): Option[Range] =
    (rng.start, rng.end) match
      case (Some(start), Some(end)) =>
        Some(
          Range(
            Position.fromScalacPosition(start),
            Position.fromScalacPosition(end)
          )
        )
      case (Some(start), None) =>
        Some(
          Range(
            Position.fromScalacPosition(start),
            Position.fromScalacPosition(start)
          )
        )
      case (None, Some(end)) =>
        Some(
          Range(
            Position.fromScalacPosition(end),
            Position.fromScalacPosition(end)
          )
        )
      case (None, None) =>
        None

case class Position(line: Int, character: Int)
object Position:
  given Codec[Position] =
    deriveCodec[Position]

  def fromScalacPosition(pos: ScalacPosition): Position =
    Position(pos.line, pos.character)

case class InverseSourcesParams(textDocument: TextDocumentIdentifier)
object InverseSourcesParams:
  given Decoder[InverseSourcesParams] = deriveDecoder[InverseSourcesParams]

case class InverseSourcesResult(targets: List[BuildTargetIdentifier])
object InverseSourcesResult:
  given Encoder[InverseSourcesResult] = deriveEncoder[InverseSourcesResult]

  def empty: InverseSourcesResult = InverseSourcesResult(Nil)

case class CleanCacheParams(targets: List[BuildTargetIdentifier])
object CleanCacheParams:
  given Decoder[CleanCacheParams] = deriveDecoder[CleanCacheParams]

case class CleanCacheResult(message: Option[String], cleaned: Boolean)
object CleanCacheResult:
  given Encoder[CleanCacheResult] = deriveEncoder[CleanCacheResult]

case class CancelParams(id: Option[Int | String])
object CancelParams:
  given Decoder[CancelParams] = deriveDecoder[CancelParams]

case class ShowMessageParams(
    `type`: MessageType,
    task: Option[TaskId],
    originId: Option[String],
    message: String
)
object ShowMessageParams:
  given Encoder[ShowMessageParams] = deriveEncoder[ShowMessageParams]

enum MessageType(val id: Int):
  case Error extends MessageType(1)
  case Warning extends MessageType(2)
  case Info extends MessageType(3)
  case Log extends MessageType(4)

object MessageType:
  given Encoder[MessageType] = Encoder.instance(_.id.asJson)

enum TaskDataKind(val id: String):
  case CompileTask extends TaskDataKind("compile-task")
  case CompileReport extends TaskDataKind("compile-report")
  case TestTask extends TaskDataKind("test-task")
  case TestReport extends TaskDataKind("test-report")
  case TestStart extends TaskDataKind("test-start")
  case TestFinish extends TaskDataKind("test-finish")

object TaskDataKind:
  given Encoder[TaskDataKind] = Encoder.instance(_.id.asJson)
  given Decoder[TaskDataKind] = Decoder[String].map {
    case "compile-task"   => TaskDataKind.CompileTask
    case "compile-report" => TaskDataKind.CompileReport
    case "test-task"      => TaskDataKind.TestTask
    case "test-report"    => TaskDataKind.TestReport
    case "test-start"     => TaskDataKind.TestStart
    case "test-finish"    => TaskDataKind.TestFinish
  }

case class CompileTask(target: BuildTargetIdentifier)
object CompileTask:
  given Encoder[CompileTask] = deriveEncoder[CompileTask]

case class CompileReport(
    target: BuildTargetIdentifier,
    originId: Option[String],
    errors: Int,
    warnings: Int,
    time: Option[Int],
    noOp: Option[Boolean]
)
object CompileReport:
  given Encoder[CompileReport] = deriveEncoder[CompileReport]
