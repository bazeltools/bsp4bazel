package afenton.bazel.bsp

import afenton.bazel.bsp.jrpc.Notification
import afenton.bazel.bsp.protocol.BuildServerCapabilities
import afenton.bazel.bsp.protocol.BuildTargetIdentifier
import afenton.bazel.bsp.protocol.CompileProvider
import afenton.bazel.bsp.protocol.Diagnostic
import afenton.bazel.bsp.protocol.DiagnosticSeverity
import afenton.bazel.bsp.protocol.InitializeBuildResult
import afenton.bazel.bsp.protocol.PublishDiagnosticsParams
import afenton.bazel.bsp.protocol.Range
import afenton.bazel.bsp.protocol.StatusCode
import afenton.bazel.bsp.protocol.TaskFinishParams
import afenton.bazel.bsp.protocol.TaskStartParams
import afenton.bazel.bsp.protocol.TextDocumentIdentifier
import io.circe.Decoder
import io.circe.Json
import io.circe.syntax.*

import java.nio.file.Path
import java.nio.file.Paths
import scala.concurrent.duration._
import scala.reflect.Typeable

trait BspHelpers:
  self: munit.FunSuite =>

  extension [A](ls: List[A])
    def select[AA: Typeable]: AA =
      ls.collectFirst { case t: AA => t } match
        case None        => fail("Expected to find type T, but didn't")
        case Some(value) => value

  extension (ns: List[Notification])
    def selectNotification[T: Decoder](method: String): T =
      val rs = decodeAndFilter[T](ns, method, _ => true)
      assert(
        rs.length == 1,
        s"Expected a single $method Notification, but ${rs.length} returned instead"
      )
      rs.head

    def selectDiagnostics(file: String): List[Diagnostic] =
      val rs = decodeAndFilter[PublishDiagnosticsParams](
        ns,
        "build/publishDiagnostics",
        pd => Paths.get(pd.textDocument.uri.getPath).endsWith(file)
      )
      assert(
        rs.length == 1,
        s"Expected a single Diagnostic for ${file}, but found ${rs.length} instead"
      )
      rs.head.diagnostics

  private def decodeAndFilter[T: Decoder](
      ns: List[Notification],
      method: String,
      filterFn: T => Boolean
  ): List[T] =
    ns.collect {
      case Notification(_, m, Some(json)) if m == method =>
        json.as[T] match {
          case Right(t)  => t
          case Left(err) => fail(err.toString)
        }
    }.filter(filterFn)

  /** Simplifies pattern match to only include relevant fields
    */
  class MiniDiagnostic(d: Diagnostic):
    def isEmpty = false
    def get: (Range, Option[DiagnosticSeverity], String) =
      (d.range, d.severity, d.message)

  object MiniDiagnostic:
    def unapply(d: Diagnostic): MiniDiagnostic = new MiniDiagnostic(d)
