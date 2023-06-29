val scala3Version = "3.2.2"

enablePlugins(GraalVMNativeImagePlugin)
enablePlugins(BuildInfoPlugin)

// <--- Updated automatically by release job
val bsp4BazelVersion = "0.0.28"
// --->

lazy val root = project
  .in(file("."))
  .settings(
    name := "bsp4bazel",
    organization := "bazeltools",
    version := bsp4BazelVersion,
    scalaVersion := scala3Version,
    libraryDependencies += "org.typelevel" %% "cats-effect" % "3.5.1",
    libraryDependencies += "co.fs2" %% "fs2-core" % "3.7.0",
    libraryDependencies += "co.fs2" %% "fs2-io" % "3.7.0",
    libraryDependencies += "io.circe" %% "circe-core" % "0.14.5",
    libraryDependencies += "io.circe" %% "circe-generic" % "0.14.5",
    libraryDependencies += "io.circe" %% "circe-parser" % "0.14.5",
    libraryDependencies += "org.typelevel" %% "cats-parse" % "0.3.9",
    libraryDependencies += "com.monovore" %% "decline" % "2.4.1",
    libraryDependencies += "com.monovore" %% "decline-effect" % "2.4.1",
    libraryDependencies += "org.scalameta" %% "munit" % "0.7.29" % Test,
    libraryDependencies += "org.scalameta" %% "munit-scalacheck" % "0.7.29" % Test,
    libraryDependencies += "org.typelevel" %% "munit-cats-effect-3" % "1.0.7" % Test,
    maintainer := "Aish Fenton",
    scalacOptions ++= Seq(
      "-explain"
    ),
    graalVMNativeImageOptions ++= Seq(
      "--initialize-at-build-time",
      "--no-fallback" // Bakes-in run-time reflection (alternately: --auto-fallback, --force-fallback)
    ),
    Compile / PB.targets := Seq(
      scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"
    ),
    buildInfoKeys := Seq[BuildInfoKey](
      name,
      version,
      scalaVersion,
      sbtVersion,
      "bspVersion" -> "2.0.0-M2"
    ),
    buildInfoPackage := "bazeltools.bsp4bazel"
  )
