ThisBuild / tlBaseVersion := "0.1"

ThisBuild / developers := List(
  tlGitHubDev("armanbilge", "Arman Bilge")
)
ThisBuild / startYear := Some(2022)

ThisBuild / crossScalaVersions := Seq("3.1.3", "2.13.9")
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))
// ThisBuild / tlJdkRelease := Some(8)
ThisBuild / githubWorkflowOSes :=
  Seq("ubuntu-20.04", "ubuntu-22.04", "macos-11", "macos-12")

ThisBuild / githubWorkflowBuildPreamble +=
  WorkflowStep.Run(
    List("sudo apt-get update", "sudo apt-get install libcurl4-openssl-dev"),
    name = Some("Install libcurl"),
    cond = Some("startsWith(matrix.os, 'ubuntu')"),
  )
ThisBuild / githubWorkflowBuildPostamble ~= {
  _.filterNot(_.name.contains("Check unused compile dependencies"))
}

val catsEffectVersion = "3.3.14"
val http4sVersion = "0.23.16"
val munitCEVersion = "2.0.0-M3"

ThisBuild / nativeConfig ~= { c =>
  val osName = Option(System.getProperty("os.name"))
  val isMacOs = osName.exists(_.toLowerCase().contains("mac"))
  if (isMacOs) { // brew-installed curl
    c.withLinkingOptions(c.linkingOptions :+ "-L/usr/local/opt/curl/lib")
  } else c
}

lazy val root = project.in(file(".")).enablePlugins(NoPublishPlugin).aggregate(curl, example)

lazy val curl = project
  .in(file("curl"))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "http4s-curl",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-effect" % catsEffectVersion,
      "org.http4s" %%% "http4s-client" % http4sVersion,
      "org.typelevel" %%% "munit-cats-effect" % munitCEVersion % Test,
    ),
  )

lazy val example = project
  .in(file("example"))
  .enablePlugins(ScalaNativePlugin, NoPublishPlugin)
  .dependsOn(curl)
  .settings(
    libraryDependencies ++= Seq(
      "org.http4s" %%% "http4s-circe" % http4sVersion
    )
  )
