package afenton.bazel.bsp.jrpc

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import fs2.Chunk
import munit.ScalaCheckSuite
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import JRpcConsoleCodec.windowsLines
import io.circe.syntax.*
import io.circe.Json
import afenton.bazel.bsp.Logger

import afenton.bazel.bsp.jrpc.GenJson
class JRpcTest extends munit.CatsEffectSuite with ScalaCheckSuite:

  val noLog = Logger.noOp

  test("should process a complete message") {

    val stream: Stream[IO, String] = Stream.emit(
      windowsLines(
        "Content-Length: 12",
        "",
        """{ "jsonrpc": "2.0", "id": "1", "method": "test" }""",
        "",
        ""
      )
    )
    val result = stream.through(jRpcParser(noLog)).compile.toList

    assertIO(
      result,
      List(
        Request("2.0", "1", "test", None)
      )
    )
  }

  test("should not process a parital message") {
    val stream: Stream[IO, String] = Stream.emit(
      windowsLines(
        "Content-Length: 12",
        ""
      )
    )
    val result = stream.through(jRpcParser(noLog)).compile.toList
    assertIO(result, List())
  }

  test("should parse a message split across chunks") {
    val stream: Stream[IO, String] =
      Stream.emits(
        List(
          windowsLines(
            "Content-Length: 12",
            ""
          ),
          "\r\n",
          windowsLines(
            """{ "jsonrpc": "2.0", "id": "1", "method": "test" }"""
          )
        )
      )

    val result = stream.through(jRpcParser(noLog)).compile.toList
    assertIO(
      result,
      List(
        Request("2.0", "1", "test", None)
      )
    )
  }

  test("should parse a complete message, but ignore following partial") {
    val stream: Stream[IO, String] = Stream.emit(
      windowsLines(
        "Content-Length: 12",
        "",
        """{ "jsonrpc": "2.0", "id": "1", "method": "test" }""",
        "Content-Length: 12"
      )
    )
    val result = stream.through(jRpcParser(noLog)).compile.toList

    assertIO(
      result,
      List(
        Request("2.0", "1", "test", None)
      )
    )
  }

  property("should work with any message") {
    forAll(GenMessage.genStream) { stream => }
  }

object GenMessage:

  private val genMessage: Gen[String] =
    for
      ct <- Arbitrary.arbitrary[Boolean]
      json <- GenJson.gen
      resp = Response("2.0", Some(1), Some(json), None)
    yield JRpcConsoleCodec.encode(resp, ct)

  private val genMessages: Gen[String] =
    for ms <- Gen.listOf(genMessage)
    yield windowsLines(ms*)

  val genStream: Gen[Stream[IO, String]] =
    for
      ms <- genMessage
      n <- Gen.choose(1, ms.length)
    yield
      val groups =
        ms.split("\r\n").grouped(n).map(ls => windowsLines(ls*)).toList
      Stream.emits(groups)
