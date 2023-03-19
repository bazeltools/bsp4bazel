package bazeltools.bsp4bazel.protocol

import bazeltools.bsp4bazel.Logger
import bazeltools.bsp4bazel.jrpc.JRpcClient
import bazeltools.bsp4bazel.jrpc.Message
import bazeltools.bsp4bazel.jrpc.Notification
import cats.effect.IO
import cats.effect.std.Queue

trait BspClient:
  def publishDiagnostics(params: PublishDiagnosticsParams): IO[Unit]
  def buildTaskStart(params: TaskStartParams): IO[Unit]
  def buildTaskProgress(params: TaskProgressParams): IO[Unit]
  def buildTaskFinished(params: TaskFinishParams): IO[Unit]
  def buildShowMessage(params: ShowMessageParams): IO[Unit]

object BspClient:

  def toQueue(outQ: Queue[IO, Message], logger: Logger): BspClient =
    BspClientImpl(outQ, logger)

  private class BspClientImpl(outQ: Queue[IO, Message], logger: Logger)
      extends BspClient
      with JRpcClient:

    def sendNotification(n: Notification): IO[Unit] =
      for
        _ <- logger.info(n.method)
        _ <- outQ.offer(n)
      yield ()

    def publishDiagnostics(params: PublishDiagnosticsParams): IO[Unit] =
      sendNotification("build/publishDiagnostics", params)

    def buildTaskStart(params: TaskStartParams): IO[Unit] =
      sendNotification("build/taskStart", params)

    def buildTaskProgress(params: TaskProgressParams): IO[Unit] =
      sendNotification("build/taskProgress", params)

    def buildTaskFinished(params: TaskFinishParams): IO[Unit] =
      sendNotification("build/taskFinish", params)

    def buildShowMessage(params: ShowMessageParams): IO[Unit] =
      sendNotification("build/showMessage", params)
