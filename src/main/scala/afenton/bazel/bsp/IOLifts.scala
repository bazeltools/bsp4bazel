package afenton.bazel.bsp

import cats.effect.IO

object IOLifts {
  final inline def fromOption[A](opt: Option[A], inline message: String): IO[A] =
    opt match {
      case Some(a) => IO.pure(a)
      case None => IO.raiseError(new Exception(message))
    }
}