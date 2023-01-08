package afenton.bazel.bsp.runner

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class BazelLabelTest extends munit.CatsEffectSuite with munit.ScalaCheckSuite:

  test("should create BazelTargets from strings") {

    assertEquals(
      BazelTarget.fromString("myTarget").toTry.get,
      Some(BazelTarget.Single("myTarget"))
    )

    assertEquals(
      BazelTarget.fromString("").toTry.get,
      None
    )

    assertEquals(
      BazelTarget.fromString("all").toTry.get,
      Some(BazelTarget.AllRules)
    )

    assertEquals(
      BazelTarget.fromString("*").toTry.get,
      Some(BazelTarget.AllTargetsAndRules)
    )

  }

  test("should create Bazel paths from strings") {

    assertEquals(
      BPath.fromString("").toTry.get,
      BPath.BNil
    )

    assertEquals(
      BPath.fromString("a").toTry.get,
      "a" :: BPath.BNil
    )

    assertEquals(
      BPath.fromString("...").toTry.get,
      BPath.Wildcard
    )

    assertEquals(
      BPath.fromString("a/b/c").toTry.get,
      "a" :: "b" :: "c" :: BPath.BNil
    )

    assertEquals(
      BPath.fromString("a/b/c/...").toTry.get,
      "a" :: "b" :: "c" :: BPath.Wildcard
    )

    // Wildcard must be at end of expression
    intercept[Exception](BPath.fromString(".../a").toTry.get)
    intercept[Exception](BPath.fromString("a/b/.../").toTry.get)

  }

  test("should add wildcard to Bazel path") {
    assertEquals(
      BPath.fromString("a/b/c").toTry.get.withWildcard,
      "a" :: "b" :: "c" :: BPath.Wildcard
    )
    assertEquals(
      BPath.fromString("a/b/c/...").toTry.get.withWildcard,
      "a" :: "b" :: "c" :: BPath.Wildcard
    )
  }

  test("should create BazelLabels from strings") {
    def law(bl1: BazelLabel, bl2: BazelLabel): Unit =
      assertEquals(bl1, bl2)

    val commonExamples = List(
      BazelLabel.fromString("//...").toTry.get -> BazelLabel(
        None,
        BPath.Wildcard,
        None
      ),
      BazelLabel.fromString("//:all").toTry.get -> BazelLabel(
        None,
        BPath.BNil,
        Some(BazelTarget.AllRules)
      ),
      BazelLabel.fromString("//foo/bar:myRule").toTry.get -> BazelLabel(
        None,
        "foo" :: "bar" :: BPath.BNil,
        Some(BazelTarget.Single("myRule"))
      ),
      BazelLabel.fromString("@myRepo//foo/bar:*").toTry.get -> BazelLabel(
        Some("@myRepo"),
        "foo" :: "bar" :: BPath.BNil,
        Some(BazelTarget.AllTargetsAndRules)
      )
    )

    commonExamples.map(law)
  }

  test("should drop wildcard") {
    assertEquals(
      BazelLabel.fromString("//foo/bar/...:*").toTry.get.withoutWildcard,
      BazelLabel(
        None,
        "foo" :: "bar" :: BPath.BNil,
        Some(BazelTarget.AllTargetsAndRules)
      )
    )
  }

  test("should change to select all rules recurisvely") {
    assertEquals(
      BazelLabel.fromString("//foo/bar").toTry.get.allRulesResursive,
      BazelLabel(
        None,
        "foo" :: "bar" :: BPath.Wildcard,
        Some(BazelTarget.AllRules)
      )
    )

    assertEquals(
      BazelLabel.fromString("//foo/bar/...:*").toTry.get.allRulesResursive,
      BazelLabel(
        None,
        "foo" :: "bar" :: BPath.Wildcard,
        Some(BazelTarget.AllRules)
      )
    )
  }

  property("should round-trip paths") {
    forAll(BazelLabelTest.genBazelLabel) { bl1 =>
      val str = bl1.asString
      val bl2 = BazelLabel.fromString(str).toTry.get
      assertEquals(bl1, bl2)
    }
  }

end BazelLabelTest

object BazelLabelTest:

  private val genRepo: Gen[String] =
    Gen.alphaStr.map(s => s"@myRepo$s")

  private def genPath: Gen[BPath] =
    for
      head <- Gen.alphaStr.map(s => s"part_$s")
      path <- Gen.oneOf(
        Gen.const(BPath.BNil),
        Gen.const(BPath.Wildcard),
        Gen.delay { genPath.map(tail => BPath.BCons(head, tail)) }
      )
    yield path

  private val genTarget: Gen[BazelTarget] =
    for
      name <- Gen.alphaStr.map(s => s"myTarget_$s")
      tar <- Gen.oneOf(
        BazelTarget.AllRules,
        BazelTarget.AllTargetsAndRules,
        BazelTarget.Single(name)
      )
    yield tar

  val genBazelLabel: Gen[BazelLabel] =
    for
      repo <- Gen.option(genRepo)
      path <- genPath
      target <- Gen.option(genTarget)
    yield BazelLabel(repo, path, target)
