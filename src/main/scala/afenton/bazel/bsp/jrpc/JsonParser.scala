package afenton.bazel.bsp.jrpc

import cats.parse.{Numbers, Parser => P, Parser0 => P0, strings}
import io.circe.{Json, JsonNumber, JsonObject}

// NB: Why have a custom parser, instead of using Circe? Because we need support for partially parsing a string into Json, and leaving
// the rest in the buffer.
object JsonParser:

  def parse(str: String): Either[P.Error, (String, JsonObject)] =
    Parsers.obj.parse(str)

  def parseAll(str: String): Either[P.Error, JsonObject] =
    Parsers.obj.parseAll(str)

  object Parsers {

    val whitespace: P[Unit] = P.charIn(" \t\r\n").void
    val whitespaces0: P0[Unit] = whitespace.rep0.void

    val recurse: P[Json] = P.defer(anyJson)

    val bool: P[Json] =
      P.fromStringMap(Map("true" -> Json.True, "false" -> Json.False))

    val pnull: P[Json] =
      P.string("null").as(Json.Null)

    val num: P[JsonNumber] =
      Numbers.jsonNumber.map(JsonNumber.fromDecimalStringUnsafe(_))

    val str: P[Json] =
      strings.Json.delimited.parser.map(Json.fromString(_))

    private val listSep: P[Unit] =
      (whitespaces0.with1.soft ~ P.char(',') ~ whitespaces0).void

    private def rep[A](pa: P[A]): P0[List[A]] =
      (whitespaces0 *> P.repSep0(pa, min = 0, sep = listSep) <* whitespaces0)

    val list: P[Json] = (P.char('[') *> rep(recurse) <* P.char(']'))
      .map(Json.fromValues(_))

    val kv: P[(String, Json)] =
      strings.Json.delimited.parser ~ ((whitespaces0.with1 ~ P.char(
        ':'
      ) ~ whitespaces0) *> recurse)

    val obj: P[JsonObject] = (P.char('{') *> rep(kv) <* P.char('}'))
      .map(JsonObject.fromIterable(_))

    val anyJson: P[Json] =
      P.oneOf(
        pnull :: bool :: num.map(Json.fromJsonNumber(_)) :: str :: list :: obj
          .map(Json.fromJsonObject(_)) :: Nil
      )
  }
