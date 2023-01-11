//> using lib "org.scalameta::munit::0.7.29"

import math.Ordering.Implicits.infixOrderingOps

class ProjectTest extends munit.FunSuite {

  test("should parse SemVer versions") {
    assertEquals(
      SemVer.fromString("1.2.20"),
      Some(SemVer(1, 2, 20))
    )
  }

  test("should order SemVer versions") {
    assert(
      SemVer(1, 10, 20) < SemVer(2, 0, 1),
      "1.10.20 should be less than 2.0.1"
    )
    assert(
      SemVer(1, 2, 9) < SemVer(1, 3, 0),
      "1.2.9 should be less than 1.3.0"
    )
    assert(
      SemVer(1, 2, 20) < SemVer(1, 2, 21),
      "1.2.20 should be less than 1.2.21"
    )
  }

  test("should work like sed") {

    val lines = """
A quick brown fox
jumped over the 
lazy dog
""".trim

    val result1 = sed(lines, "over".r, "ed".r, "ing")
    assertEquals(
      result1,
      Some("""
A quick brown fox
jumping over the 
lazy dog
    """.trim
      )
    )

    val result2 = sed(lines, "not-there".r, "ed".r, "ing")
    assertEquals(result2, None)
  }

}