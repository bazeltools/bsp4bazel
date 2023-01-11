package afenton.bazel.bsp.jrpc

import afenton.bazel.bsp.Logger
import afenton.bazel.bsp.protocol.BspClient
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
import io.circe.generic.semiauto.*
import io.circe.syntax.*

import scala.reflect.TypeTest

sealed trait Message:
  def jsonrpc: "2.0"

object Message:

  given Encoder[Message] = Encoder.instance {
    case req: Request      => req.asJson
    case resp: Response    => resp.asJson
    case not: Notification => not.asJson
  }

  given Decoder[Message] =
    Decoder[Request].or(Decoder[Notification].widen).or(Decoder[Response].widen)

case class Request(
    jsonrpc: "2.0",
    id: Int | String,
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
        id <- h.downField("id").as[Int | String]
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

  given Decoder[Response] =
    Decoder.instance { h =>
      for
        jsonrpc <- h
          .downField("jsonrpc")
          .as["2.0"]
        id <- h.downField("id").as[Option[Int | String]]
        result <- h.downField("result").as[Option[Json]]
        error <- h.downField("error").as[Option[ResponseError]]
      yield Response(jsonrpc, id, result, error)
    }

case class Notification(jsonrpc: "2.0", method: String, params: Option[Json])
    extends Message
object Notification:
  given Encoder[Notification] =
    Encoder.instance { obj =>
      Json.obj(
        "jsonrpc" -> obj.jsonrpc.asJson,
        "method" -> obj.method.asJson,
        "params" -> obj.params.asJson
      )
    }

  given Decoder[Notification] =
    Decoder.instance { h =>
      for
        jsonrpc <- h
          .downField("jsonrpc")
          .as["2.0"]
        method <- h.downField("method").as[String]
        params <- h.downField("params").as[Option[Json]]
      yield Notification(jsonrpc, method, params)
    }

case class ResponseError(code: Int, message: String, data: Option[Json])
object ResponseError:
  given Codec[ResponseError] = deriveCodec[ResponseError]

given decodeIntOrString: Decoder[Int | String] =
  Decoder.instance(h => h.as[String].orElse(h.as[Int]))

given encodeIntOrString: Encoder[Int | String] =
  Encoder.instance {
    case s: String => s.asJson
    case i: Int    => i.asJson
  }

def jRpcParser(logger: Logger): Pipe[IO, String, Message] =
  def go(
      remaining: String,
      stream: Stream[IO, String]
  ): Pull[IO, Message, Unit] =
    stream.pull.uncons1.flatMap {
      case None =>
        Pull.done
      case Some((h, t)) =>
        JRpcConsoleCodec.partialParse(remaining + h) match
          case Right((leftOver, json)) =>
            Pull.output1(json) >> go(leftOver, t)
          case Left(err) =>
            Pull.eval(logger.error(s"RECURSE ERROR with $err $remaining $h")) >>
              // Might be partial json string, so try with rest of stream
              go(remaining + h, t)
    }

  (in: Stream[IO, String]) => go("", in).stream

object UnitJson:
  def unapply(json: Json): Boolean = json.asObject match
    case Some(obj) if obj.isEmpty => true
    case _                        => false

def messageDispatcher(
    fn: PartialFunction[String, RpcFunction[_, _]]
): Pipe[IO, Message, Response | Unit] =
  (in: Stream[IO, Message]) =>
    in.flatMap {
      case notification: Notification =>
        Stream
          .eval[IO, Unit] {
            fn(notification.method)
              .apply(
                notification.params.getOrElse(().asJson)
              )
              .map(_.as[Unit])
          }
      case request: Request =>
        Stream
          .eval[IO, Json] {
            fn(request.method).apply(request.params.getOrElse(().asJson))
          }
          .map {
            case _ @UnitJson() =>
              Response("2.0", Some(request.id), None, None)
            case json =>
              Response("2.0", Some(request.id), Some(json), None)
          }
      case _ => throw Exception("shouldn't get here")
    }

case class RpcFunction[A: Decoder, B: Encoder](fn: A => IO[B]):
  def decode(params: Json): IO[A] = IO.fromEither(Decoder[A].decodeJson(params))
  def encode(response: B): Json = Encoder[B].apply(response)
  def apply(json: Json): IO[Json] =
    for
      a <- decode(json)
      b <- fn(a)
    yield encode(b)

object JRpcConsoleCodec {

  val parser: P[JsonObject] = P.recursive[JsonObject] { recurse =>
    val clHeader: P[String] =
      P.string("Content-Length:") *> P.char(' ').? *> Rfc5234.digit
        .repAs[String]

    val ctHeader: P[String] =
      P.string("Content-Type:") *> P.char(' ').? *> P.charsWhile(c => c != '\r')

    val headers: P[NonEmptyList[String]] =
      (P.oneOf(clHeader :: ctHeader :: Nil) <* P.string("\r\n")).rep

    val body: P[JsonObject] =
      JsonParser.Parsers.obj

    (headers *> P.string("\r\n") *> body)
  }

  def partialParse(str: String): Either[String, (String, Message)] =
    parser
      .parse(str)
      .flatMap { (a, b) =>
        Decoder[Message].decodeJson(Json.fromJsonObject(b)).map(b => (a, b))
      }
      .leftMap(_.toString)

  def encode(msg: Message, includeContentType: Boolean): String =
    def cond(p: Boolean, e: String) =
      if p then (e :: Nil) else Nil

    val jsonStr = msg.asJson.deepDropNullValues.noSpaces
    val lines =
      s"Content-Length: ${jsonStr.length}" ::
        cond(
          includeContentType,
          "Content-Type: application/vscode-jsonrpc; charset=utf-8"
        ) ::: (
          "" ::
            jsonStr ::
            Nil
        )

    windowsLines(lines*)

  def windowsLines(lines: String*): String =
    lines.mkString("\r\n")

}

trait JRpcClient:
  def sendNotification[A: Encoder](method: String, params: A): IO[Unit] =
    val json = Encoder[A].apply(params)
    sendNotification(Notification("2.0", method, Some(json)))

  def sendNotification(n: Notification): IO[Unit]
