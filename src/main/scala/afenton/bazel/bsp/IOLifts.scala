package afenton.bazel.bsp

import cats.effect.IO
import scala.quoted.*

object IOLifts {
  /** Convert an Option to an IO or make a good error message
   */
  final inline def fromOption[A](inline opt: Option[A]): IO[A] =
    ${fromOptionImpl('opt)}

  def fromOptionImpl[A](expr: Expr[Option[A]])(using ctx: Quotes, ta: Type[A]) = {
    val rootPosition = ctx.reflect.Position.ofMacroExpansion
    val file = Expr(rootPosition.sourceFile.path)
    val line = Expr(rootPosition.startLine + 1)
    val show = Expr(expr.show)

    '{
      val res = $expr
      if (res.isDefined) then IO.pure(res.get)
      else IO.raiseError(new Exception(s"expected ${${show}} to be defined in file: ${${file}} at line: ${${line}}"))
    }
  }
}