ThisBuild / tlBaseVersion := "0.0"

ThisBuild / developers := List(
  tlGitHubDev("armanbilge", "Arman Bilge")
)
ThisBuild / startYear := Some(2022)

ThisBuild / crossScalaVersions := Seq("3.1.3", "2.13.8")

val http4sVersion = "0.23.14-101-02562a0-SNAPSHOT"
val munitCEVersion = "2.0-4e051ab-SNAPSHOT"
ThisBuild / resolvers ++= Resolver.sonatypeOssRepos("snapshots")

lazy val root = project.in(file(".")).enablePlugins(NoPublishPlugin).aggregate(curl)

lazy val curl = project
  .in(file("curl"))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "http4s-curl",
    libraryDependencies ++= Seq(
      "com.armanbilge" %%% "http4s-client" % http4sVersion,
      "com.armanbilge" %%% "munit-cats-effect" % munitCEVersion,
    ),
  )
