package afenton.bazel.bsp

import cats.effect.kernel.Resource.ExitCase
import cats.effect.IO
import cats.effect.ExitCode
import cats.syntax.all.*
import fs2.io.file.Files
import fs2.io.file.Path
import afenton.bazel.bsp.runner.BazelRunner
import afenton.bazel.bsp.runner.BazelRunner.BazelWrapper
import cats.data.EitherT

object Verifier:

  lazy val Cwd = Path("")

  lazy val runner: IO[BazelRunner] =
    for wrapper <- BazelWrapper.default(Cwd.toNioPath)
    yield BazelRunner.default(
      Cwd.toNioPath.toAbsolutePath,
      wrapper,
      Logger.noOp
    )

  def validateSetup: IO[List[(String, Either[String, Unit])]] =
    List(
      "Bsp Configuration" -> hasBspConfigFile,
      "Bsp Targets Specified" -> bazelHasBspTargets
      // bazelHasDiagnosticsEnabled,
    ).traverse((n, eio) => eio.map(e => (n, e)))

  def bazelHasBspTargets: IO[Either[String, Unit]] =
    val msg = """Bazel has no BSP targets specified. See TODO to setup"""

    val et =
      for
        wrapper <- BazelWrapper.default(Cwd.toNioPath).attemptT
        runner <- runner.attemptT
        targets <- runner.bspTargets.attemptT
        result <- EitherT.cond(!targets.isEmpty, (), new Exception(msg))
      yield result

    et.leftMap(_.toString).value

  def bazelHasDiagnosticsEnabled: IO[Either[String, Unit]] =
    val msg =
      """Bazel not configured to produce diagnostics messages from Bazel. See TODO to setup"""

    // How to verify this???
    ???

  def hasBspConfigFile: IO[Either[String, Unit]] =
    val msg =
      """Missing BSP configuration file (i.e. .bsp/bazel-bsp.json) in the current project directory. Run "bazel-bsp --setup" to create."""

    for e <- Files[IO].exists(Cwd / ".bsp" / "bazel-bsp.json")
    yield Either.cond(e, (), msg)
