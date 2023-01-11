package afenton.bazel.bsp.jrpc

import cats.syntax.all.*
import io.circe.{Json, JsonNumber, JsonObject}
import munit.ScalaCheckSuite
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalacheck.Prop.*

class JsonParserTest extends ScalaCheckSuite:

  def assertParsesAndEqual[T](
      result: Either[cats.parse.Parser.Error, T],
      expected: T
  ): Unit = result match {
    case Left(err) => fail(err.show)
    case Right(t)  => assertEquals(t, expected)
  }

  def num(str: String): Json =
    Json.fromJsonNumber(JsonNumber.fromDecimalStringUnsafe(str))

  test("should handle Json numbers") {
    // NB: In Json, numbers with fractional values must have digits before the decimal point
    val result = JsonParser.parseAll("""{
        "n1" : 210,
        "n2" : -210,
        "n3" : 0.05,
        "n4" : 1.0E+2
      }""")
    assertParsesAndEqual(
      result,
      JsonObject(
        "n1" -> num("210"),
        "n2" -> num("-210"),
        "n3" -> num("0.05"),
        "n4" -> num("1.0E+2")
      )
    )
  }

  test("should handle escape chars in a string") {
    val result = JsonParser.parseAll("""{
        "escaped": "\"\b\t\r\n\\",
        "unicode": "\u6211\u662F\u5730\u7403\uD83C\uDF0D"
      }""")
    assertParsesAndEqual(
      result,
      JsonObject(
        "escaped" -> Json.fromString("\"\b\t\r\n\\"),
        "unicode" -> Json.fromString(
          "\u6211\u662F\u5730\u7403\uD83C\uDF0D"
        )
      )
    )
  }

  test("should handle more content, after Json string") {
    val partial = """{"test":1},{"""
    JsonParser.parse(partial) match {
      case Left(err)     => fail(err.show)
      case Right((r, _)) => assertEquals(r, ",{")
    }
  }

  property("should parse valid Json") {
    forAll(GenJson.gen) { json =>
      val jsonStr = json.noSpaces
      JsonParser.parseAll(jsonStr) match {
        case Right(obj) =>
          assertEquals(json, Json.fromJsonObject(obj))
        case Left(err) => fail(err.toString)
      }
    }
  }

object GenJson:

  private val genJValue: Gen[Json] =
    Gen.oneOf(
      Arbitrary.arbitrary[Long].map(Json.fromLong),
      Arbitrary.arbitrary[Double].map(d => Json.fromDoubleOrNull(d)),
      Arbitrary.arbitrary[Boolean].map(Json.fromBoolean),
      Arbitrary.arbitrary[String].map(Json.fromString),
      Gen.const(Json.Null)
    )

  private def genJArray(depth: Int): Gen[Json] =
    Gen.listOf(genJson(depth)).map(Json.fromValues)

  private def genJson(depth: Int): Gen[Json] =
    if (depth > 2)
      genJValue
    else
      Gen.frequency(
        1 -> genJArray(depth + 1),
        1 -> genJObject(depth + 1),
        8 -> genJValue
      )

  private def genKv(depth: Int): Gen[(String, Json)] = for {
    n <- Gen.asciiPrintableStr
    v <- genJson(depth)
  } yield (n, v)

  private def genJObject(depth: Int): Gen[Json] =
    Gen.listOf(genKv(depth)).map(Json.fromFields)

  def gen: Gen[Json] =
    genJObject(1)
