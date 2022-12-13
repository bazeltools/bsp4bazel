package afenton.bazel.bsp.jrpc

import cats.data.NonEmptyList
import cats.effect.IO
import cats.parse.Rfc5234
import cats.parse.Parser as P
import cats.parse.Parser0 as P0
import cats.syntax.all.*
import fs2.Pipe
import fs2.Pull
import fs2.Stream
import io.circe._
import io.circe.syntax.*
import afenton.bazel.bsp.jrpc.PartialJson.JObject
import afenton.bazel.bsp.Logger
import scala.reflect.TypeTest
import io.circe.generic.semiauto.*

sealed trait Message:
  def jsonrpc: "2.0"

object Message:

  given Encoder[Message] = Encoder.instance {
    case req: Request   => req.asJson
    case resp: Response => resp.asJson
  }

case class Request(
    jsonrpc: "2.0",
    id: Option[Int | String],
    method: String,
    params: Option[Json]
) extends Message

object Request:

  given Decoder["2.0"] =
    Decoder[String].emap { str =>
      Either.cond(str == "2.0", "2.0", "Jsonrpc version must be 2.0")
    }

  given Decoder[Request] =
    Decoder.instance { h =>
      for
        jsonrpc <- h
          .downField("jsonrpc")
          .as["2.0"]
        id <- h.downField("id").as[Option[Int | String]]
        method <- h.downField("method").as[String]
        params <- h.downField("params").as[Option[Json]]
      yield Request(jsonrpc, id, method, params)
    }

  given Encoder[Request] =
    Encoder.instance { obj =>
      Json.obj(
        "jsonrpc" -> obj.jsonrpc.asJson,
        "id" -> obj.id.asJson,
        "method" -> obj.method.asJson,
        "params" -> obj.params.asJson
      )
    }

case class Response(
    jsonrpc: "2.0",
    id: Option[Int | String],
    result: Option[Json],
    error: Option[ResponseError]
) extends Message

object Response:
  given Encoder[Response] =
    Encoder.instance { obj =>
      Json.obj(
        "jsonrpc" -> obj.jsonrpc.asJson,
        "id" -> obj.id.asJson,
        "result" -> obj.result.asJson,
        "error" -> obj.error.asJson
      )
    }

case class ResponseError(code: Int, message: String, data: Option[Json])
object ResponseError:
  given Encoder[ResponseError] = deriveEncoder[ResponseError]

given decodeIntOrString: Decoder[Int | String] =
  Decoder.instance(h => h.as[Int].orElse(h.as[String]))

given encodeIntOrString: Encoder[Int | String] =
  Encoder.instance {
    case s: String => s.asJson
    case i: Int    => i.asJson
  }

def jRpcParser(logger: Logger): Pipe[IO, String, Request] =
  def go(
      remaining: String,
      stream: Stream[IO, String],
      logger: Logger
  ): Pull[IO, Request, Unit] =
    stream.pull.uncons1.flatMap {
      case None =>
        Pull.done
      case Some((h, t)) =>
        JRpcConsoleCodec.partialParse(remaining + h) match
          case Right((leftOver, json)) =>
            Pull.output1(json) >> go(leftOver, t, logger)
          case Left(err) =>
            System.err.println(("RECURSE ERROR with ", err, remaining, h))
            // Might be partial json string, so try with rest of stream
            go(remaining + h, t, logger)
    }

  (in: Stream[IO, String]) => go("", in, logger).stream

private def unitJson(json: Json): Boolean = json.asObject match
  case Some(obj) if obj.isEmpty => true
  case _                        => false

def messageDispatcher(
    fn: PartialFunction[String, RpcFunction[_, _]]
): Pipe[IO, Request, Response] =
  (in: Stream[IO, Request]) =>
    in.flatMap { request =>
      Stream
        .eval[IO, Json] {
          fn(request.method).apply(request.params.getOrElse(().asJson))
        }
        // remove empty responses
        .filterNot(unitJson)
        .map { json =>
          Response("2.0", request.id, Some(json), None)
        }
    }

case class RpcFunction[A: Decoder, B: Encoder](fn: A => IO[B]):
  def decode(params: Json): A = Decoder[A].decodeJson(params).toTry.get
  def encode(response: B): Json = Encoder[B].apply(response)
  def apply(json: Json): IO[Json] =
    val params = decode(json)
    fn(params).map(resp => encode(resp))

object JRpcConsoleCodec {

  val parser: P[JObject] = P.recursive[JObject] { recurse =>
    val clHeader: P[String] =
      P.string("Content-Length:") *> P.char(' ').? *> Rfc5234.digit
        .repAs[String]

    val ctHeader: P[String] =
      P.string("Content-Type:") *> P.char(' ').? *> P.charsWhile(c => c != '\r')

    val headers: P[NonEmptyList[String]] =
      (P.oneOf(clHeader :: ctHeader :: Nil) <* P.string("\r\n")).rep

    val body: P[JObject] =
      PartialJson.Parsers.obj

    (headers *> P.string("\r\n") *> body)
  }

  def partialParse(str: String): Either[String, (String, Request)] =
    parser
      .parse(str)
      .flatMap { (a, b) =>
        io.circe.parser.decode[Request](b.asString).map(b => (a, b))
      }
      .leftMap(_.toString)

  def encode(msg: Message, includeContentType: Boolean): String =
    def cond(p: Boolean, e: String) =
      if (p)
        List(e)
      else
        Nil

    val jsonStr = msg.asJson.noSpaces
    val lines =
      List(
        s"Content-Length: ${jsonStr.length}"
      ) ++ cond(
        includeContentType,
        "Content-Type: application/vscode-jsonrpc; charset=utf-8"
      ) ++ List(
        "",
        jsonStr
      )

    windowsLines(lines*)

  def windowsLines(lines: String*): String =
    lines.mkString("\r\n") // + "\r\n"

}
