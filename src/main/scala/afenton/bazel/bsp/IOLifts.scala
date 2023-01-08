package afenton.bazel.bsp

import cats.effect.IO
import scala.quoted.*
import java.nio.file.Paths
import scala.util.control.NonFatal

object IOLifts {

  /** Convert an Option to an IO or make a good error message returns a
    * NoSuchElementException, the same as None.get
    */
  final inline def fromOption[A](inline opt: Option[A]): IO[A] =
    ${ fromOptionImpl[A, A]('opt, '{ a => IO.pure[A](a) }) }

  extension [A](inline option: Option[A]) {
    inline def asIO: IO[A] = fromOption(option)
    inline def mapToIO[B](inline fn: A => IO[B]): IO[B] =
      ${ fromOptionImpl('option, 'fn) }
  }

  def fromOptionImpl[A: Type, B: Type](
      expr: Expr[Option[A]],
      fn: Expr[A => IO[B]]
  )(using ctx: Quotes): Expr[IO[B]] = {
    val rootPosition = ctx.reflect.Position.ofMacroExpansion
    val absFile: String = rootPosition.sourceFile.path
    val file =
      try {
        Paths.get("").toAbsolutePath.relativize(Paths.get(absFile)).toString
      } catch {
        case NonFatal(_) => absFile
      }
    val line = rootPosition.startLine + 1
    val msg = Expr(
      s"expected ${expr.show} to be defined in file: $file at line: $line"
    )

    val errorCase = '{
      IO.raiseError[B](new java.util.NoSuchElementException($msg))
    }

    expr match {
      case '{ Some[A]($a) } => Expr.betaReduce('{ $fn(${ a }) })
      case '{ None }        => errorCase
      case _ =>
        '{
          val res = $expr
          if (res.isDefined) then ${ Expr.betaReduce('{ $fn(res.get) }) }
          else $errorCase
        }
    }
  }

  /*
  //useful for debugging at the repl

  inline def show[A](inline a: A): A =
    ${showImpl('a)}

  def showImpl[A: Type](expr: Expr[A])(using Quotes): Expr[A] = {
    println(expr.show)
    expr
  }
   */
}
