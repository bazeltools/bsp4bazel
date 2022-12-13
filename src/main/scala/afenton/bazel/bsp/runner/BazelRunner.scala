package afenton.bazel.bsp.runner

import afenton.bazel.bsp.BuildTargetIdentifier
import cats.effect.IO
import java.nio.file.Path
import io.bazel.rules_scala.diagnostics.diagnostics.Diagnostic
import io.bazel.rules_scala.diagnostics.diagnostics.TargetDiagnostics
import java.nio.file.Files
import fs2.Compiler.Target

trait BazelRunner:
  def compile(target: BuildTargetIdentifier): IO[TargetDiagnostics]
  def clean(target: BuildTargetIdentifier): IO[Unit]

case class MyBazelRunner(base: Path) extends BazelRunner:
  def clean(target: BuildTargetIdentifier): IO[Unit] = ???

  def bazelDiagnostic(file: Path): IO[TargetDiagnostics] =
    IO {
      val bytes = Files.readAllBytes(file)
      TargetDiagnostics.parseFrom(bytes)
    }

  def compile(
      target: BuildTargetIdentifier
  ): IO[TargetDiagnostics] =
    val ps = SubProcess.runCommand(base, "./bazel", "build", "//...")()

    ps.flatMap { er =>
      if er.exitCode != 0 then
        IO.raiseError(throw new Exception(s"Bazel exited with ${er.exitCode}"))
      else bazelDiagnostic(base)
    }
