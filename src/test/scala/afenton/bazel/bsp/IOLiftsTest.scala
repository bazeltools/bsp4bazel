package afenton.bazel.bsp

import IOLifts.{asIO, fromOption}

class IOLiftsTest extends munit.CatsEffectSuite {
  test("fromOption(Some(x))") {
    fromOption(Some(1)).assertEquals(1)
  }

  test("fromOption(Option(x))") {
    fromOption(Option(1)).assertEquals(1)
  }

  test("fromOption(None)") {
    fromOption(None).attempt
      .map(_.left.map { e =>
        e.isInstanceOf[java.util.NoSuchElementException] &&
        e.getMessage.contains("IOLiftsTest.scala") &&
        e.getMessage.contains("line: 15")
      })
      .assertEquals(Left(true))
  }

  test("fromOption(Option(null))") {
    fromOption(Option(null)).attempt
      .map(_.left.map { e =>
        e.isInstanceOf[java.util.NoSuchElementException] &&
        e.getMessage.contains("IOLiftsTest.scala") &&
        e.getMessage.contains("line: 26")
      })
      .assertEquals(Left(true))
  }

  test("{ val x = Some(1); fromOption(x) }") {
    { val x = Some(1); fromOption(x) }
      .assertEquals(1)
  }

  test("{ val x: Option[Int] = None; fromOption(x) }") {
    { val x: Option[Int] = None; fromOption(x) }.attempt
      .map(_.left.map { e =>
        e.isInstanceOf[java.util.NoSuchElementException] &&
        e.getMessage.contains("IOLiftsTest.scala") &&
        e.getMessage.contains("line: 42")
      })
      .assertEquals(Left(true))
  }

  test("Some(x).asIO") {
    Some(1).asIO.assertEquals(1)
  }

  test("Option(x).asIO") {
    Option(1).asIO.assertEquals(1)
  }

  test("None.asIO") {
    None.asIO.attempt
      .map(_.left.map { e =>
        e.isInstanceOf[java.util.NoSuchElementException] &&
        e.getMessage.contains("IOLiftsTest.scala") &&
        e.getMessage.contains("line: 61")
      })
      .assertEquals(Left(true))
  }
}
