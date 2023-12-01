package bazeltools.bsp4bazel

import java.nio.file.Path
import java.nio.file.StandardOpenOption

import cats.effect.IO
import fs2.io.file.Files
import fs2.Stream

object BspSetup:

  private lazy val bspConfig: String = s"""
{
    "name": "Bsp4Bazel",
    "version": "${BuildInfo.version}",
    "bspVersion": "${BuildInfo.bspVersion}",
    "languages": [
        "scala"
    ],
    "argv": [
        "bsp4bazel"
    ]
}
"""

  def writeBspConfig(workspaceRoot: Path): IO[Unit] =
    val toPath = workspaceRoot.resolve(".bsp")
    for
      _ <- Files[IO].createDirectories(toPath)
      _ <- Stream
        .emits(bspConfig.getBytes())
        .through(
          Files[IO].writeAll(
            toPath.resolve("bsp4bazel.json"),
            List(
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING
            )
          )
        )
        .compile
        .drain
      _ <- IO.println(s"Wrote setup config to ${toPath}")
    yield ()
