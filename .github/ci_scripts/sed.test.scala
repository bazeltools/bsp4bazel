//> using file "project.scala"

class SedTest extends munit.FunSuite {

  test("should work like sed") {

    val lines = """
A quick brown fox
jumped over the 
lazy dog
""".trim

    val result = performSed(lines, "over".r, "ed".r, "ing")
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