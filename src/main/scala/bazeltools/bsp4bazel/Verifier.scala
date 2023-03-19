package bazeltools.bsp4bazel

import cats.effect.kernel.Resource.ExitCase
import cats.effect.IO
import cats.effect.ExitCode
import cats.syntax.all.*
import fs2.io.file.Files
import fs2.io.file.Path
import bazeltools.bsp4bazel.runner.BazelRunner
import bazeltools.bsp4bazel.runner.BazelRunner.BazelWrapper
import cats.data.EitherT

import bazeltools.bsp4bazel.Logger

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
      """Missing BSP configuration file (i.e. .bsp/bsp4bazel.json) in the current project directory. Run "bsp4bazel --setup" to create."""

    for e <- Files[IO].exists(Cwd / ".bsp" / "bsp4bazel.json")
    yield Either.cond(e, (), msg)
