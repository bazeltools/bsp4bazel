//> using scala "3.2"
//> using lib "com.lihaoyi::os-lib:0.9.0"
//> using lib "org.scalameta::munit::0.7.29"

class SedTest extends munit.FunSuite {

  test("should work like sed") {

    val lines = """
A quick brown fox
jumped over the 
lazy dog
""".trim

    val result = sed(lines, "over".r, "ed".r, "ing")
    assertEquals(
      result,
      """
A quick brown fox
jumping over the 
lazy dog
    """.trim
    )
  }


}