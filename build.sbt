val scala3Version = "3.2.1"

// enablePlugins(JavaAppPackaging)
enablePlugins(GraalVMNativeImagePlugin)

lazy val root = project
  .in(file("."))
  .settings(
    name := "bazel-bsp",
    organization := "org.afenton",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,
    libraryDependencies += "org.typelevel" %% "cats-effect" % "3.4.4",
    libraryDependencies += "co.fs2" %% "fs2-core" % "3.3.0",
    libraryDependencies += "co.fs2" %% "fs2-io" % "3.3.0",
    libraryDependencies += "io.circe" %% "circe-core" % "0.14.3",
    libraryDependencies += "io.circe" %% "circe-generic" % "0.14.3",
    libraryDependencies += "io.circe" %% "circe-parser" % "0.14.3",
    libraryDependencies += "org.typelevel" %% "cats-parse" % "0.3.9",
    libraryDependencies += "com.monovore" %% "decline" % "2.4.0",
    libraryDependencies += "com.monovore" %% "decline-effect" % "2.4.0",
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
    )
  )
