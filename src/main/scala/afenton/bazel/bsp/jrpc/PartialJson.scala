package afenton.bazel.bsp.jrpc

import cats.parse.{Parser => P}
import cats.parse.{Parser0 => P0}

sealed trait PartialJson:
  def asString: String

// NB: Why have a custom parser, instead of using Circe? Because we need support for partially parsing a string into Json, and leaving
// the rest in the buffer.
object PartialJson:

  private def surround(left: String, inner: String, right: String) =
    s"$left$inner$right"

  // NB: JString contains the JSON string literal, complete with escape characters. We rely on Circe to
  // convert these to the chars they represent
  case class JString(str: String) extends PartialJson:
    def asString: String =
      surround("\"", str, "\"")

  case class JArray(is: List[PartialJson]) extends PartialJson:
    def asString: String =
      surround("[", is.map(_.asString).mkString(","), "]")

  case class JObject(is: List[(String, PartialJson)]) extends PartialJson:
    def asString: String =
      surround(
        "{",
        is.map { case (k, v) => s"${JString(k).asString}:${v.asString}" }
          .mkString(","),
        "}"
      )

  case object JNull extends PartialJson:
    def asString = "null"

  case class JBool(toBoolean: Boolean) extends PartialJson:
    def asString = java.lang.Boolean.toString(toBoolean)

  case class JNumber(asString: String) extends PartialJson

  def parse(str: String): Either[P.Error, (String, JObject)] =
    Parsers.obj.parse(str)

  def parseAll(str: String): Either[P.Error, JObject] =
    Parsers.obj.parseAll(str)

  object Parsers {

    val whitespace: P[Unit] = P.charIn(" \t\r\n").void
    val whitespaces0: P0[Unit] = whitespace.rep0.void

    val recurse = P.defer(anyJson)

    val bool: P[JBool] =
      P.fromStringMap(Map("true" -> JBool(true), "false" -> JBool(false)))

    val pnull: P[JNull.type] =
      P.string("null").as(JNull)

    val num: P[JNumber] =
      cats.parse.Numbers.jsonNumber.map(JNumber.apply)

    val strElement =
      (P.string("\\\\") | P.string("\\\"") | P.charWhere(_ != '"')).string

    val justStr =
      P.char('"') *> strElement.rep0.string <* P.char('"')

    val str: P[JString] =
      justStr.map(JString.apply)

    val listSep: P[Unit] =
      (whitespaces0.with1.soft ~ P.char(',') ~ whitespaces0).void

    def rep[A](pa: P[A]): P0[List[A]] =
      (whitespaces0 *> P.repSep0(pa, min = 0, sep = listSep) <* whitespaces0)

    val list: P[JArray] = (P.char('[') *> rep(recurse) <* P.char(']'))
      .map(JArray.apply)

    val kv: P[(String, PartialJson)] =
      justStr ~ ((whitespaces0.with1 ~ P.char(':') ~ whitespaces0) *> recurse)

    val obj: P[JObject] = (P.char('{') *> rep(kv) <* P.char('}'))
      .map(JObject.apply)

    val anyJson: P[PartialJson] =
      P.oneOf(pnull :: bool :: num :: str :: list :: obj :: Nil)

  }
