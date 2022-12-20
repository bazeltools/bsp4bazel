package afenton.bazel.bsp.protocol

import cats.effect.IO

trait BspClient:
  def publishDiagnostics(params: PublishDiagnosticsParams): IO[Unit]
  def buildTaskStart(params: TaskStartParams): IO[Unit]
  def buildTaskProgress(params: TaskProgressParams): IO[Unit]
  def buildTaskFinished(params: TaskFinishParams): IO[Unit]
  def buildShowMessage(params: ShowMessageParams): IO[Unit]
