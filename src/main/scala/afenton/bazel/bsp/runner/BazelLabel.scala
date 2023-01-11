package afenton.bazel.bsp.runner

import afenton.bazel.bsp.protocol.BuildTargetIdentifier
import afenton.bazel.bsp.runner.BPath.BCons
import afenton.bazel.bsp.runner.BPath.BNil
import afenton.bazel.bsp.runner.BPath.Wildcard
import cats.effect.kernel.syntax.resource
import cats.instances.tailRec
import cats.parse.Rfc5234
import cats.parse.Parser as P
import cats.parse.Parser0 as P0

import java.nio.file.Path
import java.nio.file.Paths
import scala.annotation.tailrec

sealed trait BazelTarget:
  def asString: String = this match
    case BazelTarget.AllRules           => "all"
    case BazelTarget.AllTargetsAndRules => "*"
    case BazelTarget.Single(name)       => name

object BazelTarget:
  case object AllRules extends BazelTarget
  case object AllTargetsAndRules extends BazelTarget
  case class Single(name: String) extends BazelTarget

  private[runner] val parser: P0[Option[BazelTarget]] = {
    val allRules: P[AllRules.type] =
      P.string("all").as(AllRules)

    val allTargetsAndRules: P[AllTargetsAndRules.type] =
      P.char('*').as(AllTargetsAndRules)

    val single: P[BazelTarget.Single] =
      (Rfc5234.alpha | Rfc5234.digit | P.charIn(
        "!%-@^_\"#$&'()*-+,;<=>?[]{|}~.".toCharArray
      )).rep.string.map(BazelTarget.Single.apply)

    (allRules | allTargetsAndRules | single).?
  }

  def fromString(str: String): Either[Throwable, Option[BazelTarget]] =
    parser.parseAll(str).left.map { err =>
      throw new Exception(
        s"Failed to parse BazelTarget ${str}, with errors: ${err.toString}"
      )
    }

sealed trait BPath:
  def ::(part: String): BPath =
    BPath.BCons(part, this)

  def asPath: Path =
    Paths.get(asString)

  def asString: String = this match
    // Prevent trailing /
    case BPath.BCons(h, BNil) => s"${h}"
    case BPath.BCons(h, t)    => s"${h}/${t.asString}"
    case BPath.BNil           => ""
    case BPath.Wildcard       => "..."

  /** Remove wildcard suffix
    */
  def withoutWildcard: BPath =
    def r0(path: BPath): BPath = path match
      case BCons(name, tail) => BCons(name, r0(tail))
      case Wildcard          => BNil
      case BNil              => BNil

    r0(this)

  /** Adds a wildcard suffix, if not already present (otherwise noop)
    */
  def withWildcard: BPath =
    def r0(path: BPath): BPath = path match
      case BCons(name, tail) => BCons(name, r0(tail))
      case Wildcard          => Wildcard
      case BNil              => Wildcard

    r0(this)

object BPath:
  case class BCons(name: String, tail: BPath) extends BPath
  case object Wildcard extends BPath
  case object BNil extends BPath

  def apply(parts: String*): BPath = fromList(parts.toList)

  def fromList(parts: List[String]): BPath =
    def r0(name: String, rest: List[String]): BPath = rest match
      case Nil          => BCons(name, BNil)
      case "..." :: Nil => BCons(name, Wildcard)
      case h :: t       => BCons(name, r0(h, t))

    r0(parts.head, parts.tail)

  private[runner] val restParser: P[BPath] = P.recursive { recurse =>

    val pathWildcard: P[BPath.Wildcard.type] =
      P.string("...").as(BPath.Wildcard)

    val pathName: P[String] =
      (Rfc5234.alpha | Rfc5234.digit | P.charIn(
        "-.@_".toCharArray
      )).rep.string

    val more: P[BPath] = (P.char('/') *> recurse)

    (pathWildcard |
      (pathName ~ more.?).map((h, t) =>
        BPath.BCons(h, t.getOrElse(BPath.BNil))
      ))

  }

  val parser: P0[BPath] =
    restParser.?.map(_.getOrElse(BPath.BNil))

  def fromString(str: String): Either[Throwable, BPath] =
    parser
      .parseAll(str)
      .left
      .map(err =>
        throw new Exception(
          s"Failed to parse Bazel Path ${str}, with errors: ${err.toString}"
        )
      )

case class BazelLabel(
    repo: Option[String],
    packagePath: BPath,
    target: Option[BazelTarget]
):

  def withTarget(bt: BazelTarget): BazelLabel =
    copy(target = Some(bt))

  def allRulesResursive: BazelLabel =
    copy(packagePath = packagePath.withWildcard, Some(BazelTarget.AllRules))

  def withoutWildcard: BazelLabel =
    copy(packagePath = packagePath.withoutWildcard)

  def asString: String = {
    val pre = s"${repo.getOrElse("")}//${packagePath.asString}"
    target match {
      case Some(t) => s"${pre}:${t.asString}"
      case None    => pre
    }
  }

object BazelLabel:

  def fromString(str: String): Either[Throwable, BazelLabel] =
    parse(str).left.map(e =>
      new Exception(
        s"Identifier needs to be a valid Bazel label. Instead got: ${str}. Parse Error: ${e.toString}"
      )
    )

  def fromBuildTargetIdentifier(
      bid: BuildTargetIdentifier
  ): Either[Throwable, BazelLabel] =
    fromString(bid.uri.getPath)

  def parse(str: String): Either[P.Error, BazelLabel] =
    parser.parseAll(str)

  private[runner] val parser: P0[BazelLabel] =
    val repo: P[String] =
      (P.char('@') ~ P.charsWhile(c => c != '/')).string

    ((repo.? <* P.string("//")) ~
      BPath.parser ~ (P.char(':') *> BazelTarget.parser).?)
      .map { case ((r, p), t) =>
        BazelLabel(r, p, t.flatten)
      }
