package afenton.bazel.bsp.runner

import java.nio.file.Paths
import fs2.Stream
import scala.concurrent.duration._
import cats.effect.IO
import cats.effect.std.Console

class SubProcessTest extends munit.CatsEffectSuite: 

  test("should run a process until exit, and capture stdout/stderr") {
    val er1 = SubProcess
      .from(Paths.get("/tmp"), "echo", "stdout")
      .runUntilExit
      .unsafeRunSync()

    assertEquals(er1.exitCode, 0)
    assertEquals(er1.stderrLines.unsafeRunSync().length, 0)
    assertEquals(er1.stdoutLines.unsafeRunSync(), Seq("stdout"))

    val er2 = SubProcess
      .from(Paths.get("/tmp"), "ls", "doesnotexist")
      .runUntilExit
      .unsafeRunSync()

    // Note: Specific exit code is different on Mac vs. Linux
    assert(er2.exitCode > 0)
    assert(
      er2.stderrLines.unsafeRunSync().head.endsWith("No such file or directory")
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
