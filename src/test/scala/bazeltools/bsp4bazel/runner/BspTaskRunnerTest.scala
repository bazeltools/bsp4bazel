package bazeltools.bsp4bazel.runner

import bazeltools.bsp4bazel.jrpc.Notification
import bazeltools.bsp4bazel.protocol.BuildServerCapabilities
import bazeltools.bsp4bazel.protocol.BuildTargetIdentifier
import bazeltools.bsp4bazel.protocol.CompileProvider
import bazeltools.bsp4bazel.protocol.Diagnostic
import bazeltools.bsp4bazel.protocol.InitializeBuildResult
import bazeltools.bsp4bazel.protocol.Position
import bazeltools.bsp4bazel.protocol.PublishDiagnosticsParams
import bazeltools.bsp4bazel.protocol.Range
import bazeltools.bsp4bazel.protocol.StatusCode
import bazeltools.bsp4bazel.protocol.TaskFinishParams
import bazeltools.bsp4bazel.protocol.TaskStartParams
import bazeltools.bsp4bazel.runner.BazelRunner
import bazeltools.bsp4bazel.runner.BazelLabel
import io.circe.Decoder
import io.circe.Json
import io.circe.syntax.*

import java.nio.file.Path
import java.nio.file.Paths
import scala.concurrent.duration._

import bazeltools.bsp4bazel.BspHelpers
import bazeltools.bsp4bazel.Lsp

import bazeltools.bsp4bazel.Logger
import bazeltools.bsp4bazel.runner.BPath.BNil
import bazeltools.bsp4bazel.protocol.BspServer

import cats.data.NonEmptyList
import java.net.URI
import bazeltools.bsp4bazel.runner.BazelResult.ExitCode

class BspTaskRunnerTest extends munit.CatsEffectSuite:

  // Long, because Github actions can run slooooow at times
  override val munitTimeout = 10.minute

  val projectRoot = Paths.get("").toAbsolutePath

  val packageRoots = NonEmptyList.one(BazelLabel.fromStringUnsafe("//..."))

  def strToTarget(str: String): BuildTargetIdentifier =
    BuildTargetIdentifier.bazel(BazelLabel.fromString(str).toOption.get)

  def bazelEnv(workspaceRoot: Path, packageRoots: NonEmptyList[BazelLabel]) = FunFixture[(Path, BspTaskRunner)](
    setup = { test =>
      val bbr = BspTaskRunner.default(
        workspaceRoot,
        packageRoots,
        Logger.noOp
      )
      (workspaceRoot, bbr)
    },
    teardown = { (_, bbr) => bbr.runner.shutdown }
  )

  bazelEnv(projectRoot.resolve("examples/simple-no-errors"), packageRoots)
    .test("should list all project tagets") { (root, runner) =>
      runner.bspTargets.assertEquals(
        List(
          "//src/example:example",
          "//src/example/foo:foo"
        ).map(strToTarget)
      )
    }

  bazelEnv(projectRoot.resolve("examples/simple-no-errors"), packageRoots)
    .test("should return the correct metadata for a given target") {
      (root, runner) =>
        val bti = runner
          .bspTarget(strToTarget("//src/example/foo:foo"))
          .unsafeRunSync()

        assertEquals(bti.scalaVersion, "2.12.18")
        assertEquals(bti.scalacOptions, Nil)
        assertEquals(
          bti.classpath.map(_.getFileName.toString).sorted,
          List(
            "foo-ijar.jar",
            "scala-library-2.12.18-stamped.jar",
            "scala-reflect-2.12.18-stamped.jar"
          )
        )
        assertEquals(
          bti.scalaCompileJars.map(_.getFileName.toString).sorted,
          List(
            "scala-compiler-2.12.18-stamped.jar",
            "scala-library-2.12.18-stamped.jar",
            "scala-reflect-2.12.18-stamped.jar"
          )
        )
        assertEquals(
          bti.srcs.map(_.toString).sorted,
          List(
            "src/example/foo/Bar.scala",
            "src/example/foo/Foo.scala",
          )
        )
        assertEquals(bti.targetLabel, BazelLabel.fromStringUnsafe("@//src/example/foo:foo"))
        assert(bti.semanticdbTargetRoot.endsWith("_semanticdb/foo"))
        assert(bti.semanticdbPluginjar.head.endsWith("semanticdb-scalac_2.12.18-4.8.4-stamped.jar"))
    }

//   bazelEnv(projectRoot.resolve("examples/simple-no-errors"))
//     .test("should call query in Bazel") { (root, runner) =>

//       val result = runner
//         .query("//src/example/...")
//         .unsafeRunSync()

//       assertEquals(result.exitCode, BazelResult.ExitCode.Ok)
//       assertEquals(
//         result.stdout,
//         List(
//           "//src/example:example",
//           "//src/example/foo:foo",
//           "//src/example/foo:foo_bsp"
//         )
//       )
//     }

//   bazelEnv(projectRoot.resolve("examples/simple-no-errors"))
//     .test("should call test Bazel") { (root, runner) =>

//       val result = runner
//         .test(
//           BazelLabel(
//             None,
//             "src" :: "test" :: BNil,
//             Some(BazelTarget.Single("all_tests"))
//           )
//         )
//         .unsafeRunSync()

//       assertEquals(result.exitCode, BazelResult.ExitCode.Ok)
//       assert(result.stdout.mkString("").contains("PASSED"))
//     }

//   bazelEnv(projectRoot.resolve("examples/simple-no-errors"))
//     .test("should call run Bazel") { (root, runner) =>
//       val result = runner
//         .run(
//           BazelLabel(
//             None,
//             "src" :: BNil,
//             Some(BazelTarget.Single("main_run"))
//           )
//         )
//         .unsafeRunSync()

//       assertEquals(result.exitCode, BazelResult.ExitCode.Ok)
//       assert(result.stdout.mkString("").contains("Hello World"))
//     }
