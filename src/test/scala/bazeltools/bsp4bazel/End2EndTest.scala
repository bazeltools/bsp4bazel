package bazeltools.bsp4bazel

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
import io.circe.Decoder
import io.circe.Json
import io.circe.syntax.*

import java.nio.file.Path
import java.nio.file.Paths
import scala.concurrent.duration._

import bazeltools.bsp4bazel.BspHelpers
import bazeltools.bsp4bazel.Lsp

import bazeltools.bsp4bazel.Logger

class End2EndTest extends munit.CatsEffectSuite with BspHelpers:

  // Long, because Github actions can run slooooow at times
  override val munitTimeout = 10.minute

  val projectRoot = Paths.get("").toAbsolutePath

  def bazelEnv(workspaceRoot: Path) = FunFixture[(Path, BazelRunner)](
    setup = { test =>
      val br = BazelRunner.default(
        workspaceRoot,
        Logger.noOp
      )
      // (br.shutdown >> br.clean).unsafeRunSync()
      (workspaceRoot, br)
    },
    teardown = { (_, br) => br.shutdown }
  )

  bazelEnv(projectRoot.resolve("examples/simple-no-errors"))
    .test("should successfully initialize") { (root, bazel) =>

      val (responses, notifications) = Lsp.start.shutdown
        .runIn(root)
        .unsafeRunSync()

      assertEquals(notifications, Nil)

      assertEquals(
        responses.select[InitializeBuildResult],
        InitializeBuildResult(
          "Bazel",
          BuildInfo.version,
          BuildInfo.bspVersion,
          BuildServerCapabilities(
            compileProvider = Some(CompileProvider(List("scala"))),
            inverseSourcesProvider = Some(true),
            canReload = Some(true)
          )
        )
      )

    }

  bazelEnv(projectRoot.resolve("examples/simple-no-errors"))
    .test("should compile with no errors") { (root, bazel) =>

      val (_, notifications) = Lsp.start.workspaceTargets
        .compile("//...")
        .shutdown
        .runIn(root)
        .unsafeRunSync()

      assertEquals(notifications.length, 2)

      assertEquals(
        notifications
          .selectNotification[TaskStartParams]("build/taskStart")
          .message,
        Some("Compile Started")
      )

      assertEquals(
        notifications
          .selectNotification[TaskFinishParams]("build/taskFinish")
          .status,
        StatusCode.Ok
      )
    }

  bazelEnv(projectRoot.resolve("examples/simple-with-errors"))
    .test("should compile and report 3 errors") { (root, bazel) =>

      val (_, notifications) = Lsp.start.workspaceTargets
        .compile("//...")
        .shutdown
        .runIn(root)
        .unsafeRunSync()

      assertEquals(notifications.length, 6)

      assertEquals(
        notifications
          .selectNotification[TaskStartParams]("build/taskStart")
          .message,
        Some("Compile Started")
      )

      assertEquals(
        notifications
          .selectNotification[TaskFinishParams]("build/taskFinish")
          .status,
        StatusCode.Ok
      )

      notifications.selectDiagnostics("Foo.scala").foreach {
        case MiniDiagnostic(Range(start, end), _, "not found: type BeforeT") =>
          assertEquals(start, Position(5, 32))
          assertEquals(end, Position(6, 0))

        case MiniDiagnostic(Range(start, end), _, "not found: type IntZ") =>
          assertEquals(start, Position(5, 18))
          assertEquals(end, Position(5, 22))

        case d => fail(s"Wasn't expecting diagnostic $d")
      }

      notifications.selectDiagnostics("Bar.scala").foreach {
        case MiniDiagnostic(Range(start, end), _, "not found: type StringQ") =>
          assertEquals(start, Position(3, 18))
          assertEquals(end, Position(3, 25))

        case d => fail(s"Wasn't expecting diagnostic $d")
      }
    }
