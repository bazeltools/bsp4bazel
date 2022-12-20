package afenton.bazel.bsp

import scala.io.AnsiColor
import cats.effect.IO
import cats.effect.std.Queue

trait Logger:
  def trace(msg: String*): IO[Unit]
  def info(msg: String*): IO[Unit]
  def error(msg: String*): IO[Unit]

object Logger:
  enum Level:
    case Trace, Info, Error

class QueueLogger(stdErrQ: Queue[IO, String], verbose: Boolean) extends Logger:
  private def format(level: Logger.Level, msgs: Seq[String]) =
    def fmt(level: String, color: String, msgs: Seq[String]) =
      val withVisibleLineEndings =
        msgs.map(_.replace("\r\n", "[CRLF]\n")).mkString("\n")

      s"[${color}${level}${AnsiColor.RESET}] ${color}${withVisibleLineEndings}${AnsiColor.RESET}"

    level match {
      case Logger.Level.Trace => fmt("trace", AnsiColor.CYAN, msgs)
      case Logger.Level.Info  => fmt("info", AnsiColor.GREEN, msgs)
      case Logger.Level.Error => fmt("error", AnsiColor.RED, msgs)
    }

  def trace(msgs: String*): IO[Unit] =
    if verbose then stdErrQ.offer(format(Logger.Level.Trace, msgs))
    else IO.unit

  def info(msgs: String*): IO[Unit] =
    stdErrQ.offer(format(Logger.Level.Info, msgs))

  def error(msgs: String*): IO[Unit] =
    stdErrQ.offer(format(Logger.Level.Error, msgs))
