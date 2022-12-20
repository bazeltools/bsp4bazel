package afenton.bazel.bsp.runner

class BazelLabelTest extends munit.CatsEffectSuite {

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

  test("should create BazelLabels from strings") {

    assertEquals(
      BazelLabel.fromString("//...").toTry.get,
      BazelLabel(None, BPath.Wildcard, None)
    )

    assertEquals(
      BazelLabel.fromString("//:all").toTry.get,
      BazelLabel(None, BPath.BNil, Some(BazelTarget.AllRules))
    )

    assertEquals(
      BazelLabel.fromString("//foo/bar:myRule").toTry.get,
      BazelLabel(
        None,
        "foo" :: "bar" :: BPath.BNil,
        Some(BazelTarget.Single("myRule"))
      )
    )

    assertEquals(
      BazelLabel.fromString("@myRepo//foo/bar:myRule").toTry.get,
      BazelLabel(
        Some("@myRepo"),
        "foo" :: "bar" :: BPath.BNil,
        Some(BazelTarget.Single("myRule"))
      )
    )

    assertEquals(
      BazelLabel.fromString("//foo/...:*").toTry.get,
      BazelLabel(
        None,
        "foo" :: BPath.Wildcard,
        Some(BazelTarget.AllTargetsAndRules)
      )
    )

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

}
