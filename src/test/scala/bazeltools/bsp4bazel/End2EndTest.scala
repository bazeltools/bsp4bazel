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
import bazeltools.bsp4bazel.protocol.WorkspaceBuildTargetsResult
import java.nio.file.Files
import bazeltools.bsp4bazel.protocol.UriFactory
import bazeltools.bsp4bazel.protocol.ScalaBuildTarget

import bazeltools.bsp4bazel.BspHelpers
import bazeltools.bsp4bazel.Lsp

import bazeltools.bsp4bazel.Logger

class End2EndTest extends munit.CatsEffectSuite with BspHelpers:

  // Long, because Github actions can run slooooow at times
  override val munitTimeout = 2.minute

  val projectRoot = Paths.get("").toAbsolutePath

  private def deleteDirectory(path: Path): Unit =
    if Files.exists(path) then
      Files
        .walk(path)
        .sorted(java.util.Comparator.reverseOrder)
        .forEach(Files.deleteIfExists)

  def bazelEnv(workspaceRoot: Path) = FunFixture[(Path, BazelRunner)](
    setup = { test =>
      val br = BazelRunner.default(
        workspaceRoot,
        BazelRunner.BazelWrapper.default(workspaceRoot).unsafeRunSync(),
        Logger.noOp
      )
      (br.shutdown >> br.clean).unsafeRunSync()

      deleteDirectory(workspaceRoot.resolve(".bsp/.semanticdb"))
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
    .test("should have BSP targets") { (root, bazel) =>

      val (responses, notifications) = Lsp.start.workspaceTargets.shutdown
        .runIn(root)
        .unsafeRunSync()

      assertEquals(notifications, Nil)

      val targets = responses.select[WorkspaceBuildTargetsResult].targets
      assertEquals(targets.size, 2)
      targets.foreach { target =>
        assertEquals(target.baseDirectory, Some(UriFactory.fileUri(root)))
        assertEquals(target.languageIds, List("scala"))
        assertEquals(target.dataKind, Some("scala"))

        assert(target.data.isDefined)
        val sbt = target.data.get.as[ScalaBuildTarget].toOption.get
        assertEquals(sbt.scalaVersion, "2.12.14")
        assertEquals(sbt.scalaBinaryVersion, "2.12")

        assertEquals(
          sbt.jars.map(uri => Paths.get(uri).getFileName.toString).sorted,
          List(
            "scala-compiler-2.12.14-stamped.jar",
            "scala-library-2.12.14-stamped.jar",
            "scala-reflect-2.12.14-stamped.jar"
          )
        )
      }
    }

  bazelEnv(projectRoot.resolve("examples/simple-no-errors"))
    .test("should compile with no errors") { (root, bazel) =>

      val (_, notifications) = Lsp.start.workspaceTargets
        .compile("//src:bsp_metadata_src_target")
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
        .compile("//src:bsp_metadata_src_target")
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
          assertEquals(end, Position(6, 0))

        case d => fail(s"Wasn't expecting diagnostic $d")
      }

      notifications.selectDiagnostics("Bar.scala").foreach {
        case MiniDiagnostic(Range(start, end), _, "not found: type StringQ") =>
          assertEquals(start, Position(3, 18))
          assertEquals(end, Position(4, 0))

        case d => fail(s"Wasn't expecting diagnostic $d")
      }
    }
