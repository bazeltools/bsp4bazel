package afenton.bazel.bsp.runner

import java.nio.file.Paths
import fs2.Stream
import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.std.Console

class SubProcessTest extends munit.CatsEffectSuite:

  test("should run a process until exit, and capture stdout/stderr") {
    val (ec1, errLen1, out1) = SubProcess
      .from(Paths.get("/tmp"), "echo", "stdout")
      .runUntilExit(Duration.Inf)
      .use { er =>
        for {
          stdOut <- er.stdoutLines.compile.toList
          stderrLen <- er.stderrLines.compile.fold(0) { (acc, _) => acc + 1 }
        } yield (er.exitCode, stderrLen, stdOut)
      }
      .unsafeRunSync()

    assertEquals(ec1, 0)
    assertEquals(errLen1, 0)
    assertEquals(out1, List("stdout", ""))

    val (ec2, err2) = SubProcess
      .from(Paths.get("/tmp"), "ls", "doesnotexist")
      .runUntilExit(Duration.Inf)
      .use { er =>
        for {
          stderr <- er.stderrLines.compile.toList
        } yield (er.exitCode, stderr)
      }
      .unsafeRunSync()

    // Note: Specific exit code is different on Mac vs. Linux
    assert(ec2 > 0)
    assert(
      err2.head.endsWith("No such file or directory")
    )
  }

  test("should run a process asynchronously, and stream stdout/stderr") {
    val sb = SubProcess
      .from(Paths.get("/tmp"), "cat")

    val EOT = '\u0004'

    val outIO = sb.start.use { process =>
      for
        _ <- process.in(Stream.emits(List("1", "2", "3", s"$EOT")))
        lines <- process.out
          .flatMap(s => Stream.fromIterator[IO](s.iterator, 100))
          .takeWhile(_ != EOT)
          .compile
          .toList
      yield lines.mkString("")
    }

    assertEquals(outIO.unsafeRunSync(), s"123")

  }
