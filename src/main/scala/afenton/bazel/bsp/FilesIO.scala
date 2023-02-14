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
import java.nio.file.Paths
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*
import cats.syntax.all._
import cats.effect.kernel.Resource

object FilesIO:

  private val fileSystem: FileSystem = FileSystems.getDefault

  val cwd: IO[Path] = IO.blocking(Paths.get("").toAbsolutePath.normalize)

  def readBytes(file: Path): IO[Array[Byte]] =
    IO.blocking(Files.readAllBytes(file))

  def readJson[T: Decoder](file: Path): IO[T] =
    readBytes(file)
      .flatMap { bytes =>
        val decoded = io.circe.parser.decode(new String(bytes, "utf-8"))
        IO.fromEither(decoded)
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

  def exists(file: Path): IO[Boolean] =
    IO.blocking(Files.exists(file))

  def createDirectories(path: Path): IO[Path] =
    IO.blocking(Files.createDirectories(path))

  def openZipFile(zipFile: Path): Resource[IO, ZipFile] =
    Resource.make(IO.blocking(new ZipFile(zipFile.toFile)))(f => IO.blocking(f.close))

  def unZip(zipFile: Path, destPath: Path): IO[Unit] =
    openZipFile(zipFile).use { zf => 
      for
        dest <- createDirectories(destPath)
        entries <- IO.blocking(zf.entries.asScala.toList)
        _ <- entries.map { entry =>
          val dest = destPath.resolve(entry.getName)

          IO.blocking {
            if entry.isDirectory then Files.createDirectory(dest)
            else Files.copy(zf.getInputStream(entry), dest)
          }
        }.sequence_
      yield ()
    }