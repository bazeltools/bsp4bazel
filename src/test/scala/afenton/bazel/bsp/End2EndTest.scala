package afenton.bazel.bsp

import afenton.bazel.bsp.protocol.BuildTargetIdentifier
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit
import afenton.bazel.bsp.protocol.InitializeBuildResult
import afenton.bazel.bsp.protocol.BuildServerCapabilities
import afenton.bazel.bsp.protocol.CompileProvider
import scala.reflect.Typeable

class End2EndTest extends munit.CatsEffectSuite {

  extension [A](ls: List[A])
    def select[AA: Typeable]: AA =
      ls.collectFirst { case t: AA => t } match
        case None     => fail("Expected to find type T, but didn't")
        case Some(value) => value

  test("example test that succeeds") {
    val (rs, ns) = Lsp.start
      // .compile(BuildTargetIdentifier.bazel("//..."))
      .runFor(FiniteDuration(2, TimeUnit.SECONDS))
      .unsafeRunSync()

    assertEquals(ns, Nil)

    assertEquals(
      rs.select[InitializeBuildResult],
      InitializeBuildResult(
        "Bazel",
        "0.1",
        "2.0.0-M2",
        BuildServerCapabilities(
          compileProvider = Some(CompileProvider(List("scala"))),
          inverseSourcesProvider = Some(true),
          canReload = Some(true)
        )
      )
    )

  }

}
