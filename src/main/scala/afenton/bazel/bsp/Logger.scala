package afenton.bazel.bsp

import fs2.{Pipe, Stream}
import cats.effect.IO
import cats.effect.std.{Queue, QueueSink}

import scala.io.AnsiColor

trait Logger:
  def trace(msg: => String): IO[Unit]
  def info(msg: => String): IO[Unit]
  def error(msg: => String): IO[Unit]

object Logger:
  enum Level:
    case Trace, Info, Error

  val noOp: Logger = new Logger {
    def trace(msgs: => String): IO[Unit] = IO.unit
    def info(msgs: => String): IO[Unit] = IO.unit
    def error(msgs: => String): IO[Unit] = IO.unit
  }

  def queue(
      size: Int,
      out: Pipe[IO, String, Unit],
      verbose: Boolean
  ): IO[(Logger, Stream[IO, Unit])] =
    Queue
      .bounded[IO, () => String](size)
      .map { errQ =>
        val logger =
          if verbose then QueueVerboseLogger(errQ) else QueueQuietLogger(errQ)

        val resStream = Stream
          .fromQueueUnterminated(errQ, size)
          .through { s => out(s.map(_.apply())) }

        (logger, resStream)
      }

  private inline def fmt(
      inline level: String,
      inline color: String,
      msgs: String
  ) =
    val withVisibleLineEndings = msgs.replace("\r\n", "[CRLF]\n")

    s"[${color}${level}${AnsiColor.RESET}] ${color}${withVisibleLineEndings}${AnsiColor.RESET}"

  private inline def format(inline level: Logger.Level, msgs: String) =
    inline level match {
      case Logger.Level.Trace => fmt("trace", AnsiColor.CYAN, msgs)
      case Logger.Level.Info  => fmt("info", AnsiColor.GREEN, msgs)
      case Logger.Level.Error => fmt("error", AnsiColor.RED, msgs)
    }

  private class QueueVerboseLogger(errQ: QueueSink[IO, () => String])
      extends Logger:
    def trace(msgs: => String): IO[Unit] =
      errQ.offer(() => format(Logger.Level.Trace, msgs))

    def info(msgs: => String): IO[Unit] =
      errQ.offer(() => format(Logger.Level.Info, msgs))

    def error(msgs: => String): IO[Unit] =
      errQ.offer(() => format(Logger.Level.Error, msgs))

  private class QueueQuietLogger(errQ: QueueSink[IO, () => String])
      extends Logger:
    def trace(msgs: => String): IO[Unit] = IO.unit

    def info(msgs: => String): IO[Unit] =
      errQ.offer(() => format(Logger.Level.Info, msgs))

    def error(msgs: => String): IO[Unit] =
      errQ.offer(() => format(Logger.Level.Error, msgs))
