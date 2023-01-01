package afenton.bazel.bsp

import cats.effect.IO
import fs2.Stream
import io.circe.Decoder

import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import scala.jdk.CollectionConverters.*
import scala.util.Try

object FilesIO:

  private val fileSystem: FileSystem = FileSystems.getDefault

  def readLines(file: Path): IO[Either[Throwable, List[String]]] =
    IO.blocking {
      Try {
        Files.readAllLines(file).asScala.toList
      }.toEither
    }

  def readBytes(file: Path): IO[Either[Throwable, Array[Byte]]] =
    IO.blocking {
      Try {
        Files.readAllBytes(file)
      }.toEither
    }

  def readJson[T: Decoder](file: Path): IO[Either[Throwable, T]] =
    for lines <- readLines(file)
    yield lines.map(_.mkString("\n")).flatMap { str =>
      io.circe.parser.decode(str)
    }

  /** Return a Stream[IO, Path] of files, by walking the filesystem (including
    * sub-dirs), from the given root down.
    *
    * @param root
    *   Starting directory
    * @param glob
    *   A glob pattern to filter by (i.e. "*.scala")
    * @param maxDepth
    *   How deep into sub-dirs to go, before stopping.
    */
  def walk(
      root: Path,
      glob: Option[String] = None,
      maxDepth: Int = 100
  ): Stream[IO, Path] =
    val paths = Stream
      .fromIterator[IO](
        Files
          .walk(root, maxDepth, FileVisitOption.FOLLOW_LINKS)
          .iterator
          .asScala,
        100
      )

    glob match
      case Some(glob) =>
        val globMatcher =
          fileSystem.getPathMatcher(s"glob:${glob}")

        paths.filter(p => globMatcher.matches(p.getFileName))
      case None =>
        paths
