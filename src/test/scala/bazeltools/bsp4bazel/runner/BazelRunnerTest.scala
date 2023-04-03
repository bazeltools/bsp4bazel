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

class BazelRunnerTest extends munit.CatsEffectSuite:

  // Long, because Github actions can run slooooow at times
  override val munitTimeout = 10.minute

  val projectRoot = Paths.get("").toAbsolutePath

  def bazelEnv(workspaceRoot: Path) = FunFixture[(Path, BazelRunner)](
    setup = { test =>
      val br = BazelRunner.default(
        workspaceRoot,
        Logger.noOp
      )
      (br.shutdown >> br.clean).unsafeRunSync()
      (workspaceRoot, br)
    },
    teardown = { (_, br) => br.shutdown }
  )

  bazelEnv(projectRoot.resolve("examples/simple-no-errors"))
    .test("should call build in Bazel") { (root, runner) =>

      val result = runner
        .build(
          BazelLabel(
            None,
            BPath.Wildcard,
            Some(BazelTarget.AllRules)
          )
        )
        .unsafeRunSync()

      assertEquals(result.exitCode, BazelResult.ExitCode.Ok)
    }

  bazelEnv(projectRoot.resolve("examples/simple-no-errors"))
    .test("should call query in Bazel") { (root, runner) =>

      val result = runner
        .query("//src/example/...")
        .unsafeRunSync()

      assertEquals(result.exitCode, BazelResult.ExitCode.Ok)
      assertEquals(
        result.stdout,
        List(
          "//src/example:example",
          "//src/example/foo:foo",
          "//src/example/foo:foo_bsp"
        )
      )
    }

  bazelEnv(projectRoot.resolve("examples/simple-no-errors"))
    .test("should call test Bazel") { (root, runner) =>

      val result = runner
        .test(
          BazelLabel(
            None,
            "src" :: "test" :: BNil,
            Some(BazelTarget.Single("all_tests"))
          )
        )
        .unsafeRunSync()

      assertEquals(result.exitCode, BazelResult.ExitCode.Ok)
      assert(result.stdout.mkString("").contains("PASSED"))
    }

  bazelEnv(projectRoot.resolve("examples/simple-no-errors"))
    .test("should call run Bazel") { (root, runner) =>
      val result = runner
        .run(
          BazelLabel(
            None,
            "src" :: BNil,
            Some(BazelTarget.Single("main_run"))
          )
        )
        .unsafeRunSync()

      assertEquals(result.exitCode, BazelResult.ExitCode.Ok)
      assert(result.stdout.mkString("").contains("Hello World"))
    }
