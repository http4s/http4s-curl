ThisBuild / tlBaseVersion := "0.0"

ThisBuild / developers := List(
  tlGitHubDev("armanbilge", "Arman Bilge")
)
ThisBuild / startYear := Some(2022)

ThisBuild / crossScalaVersions := Seq("3.1.3", "2.13.8")
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))
ThisBuild / tlJdkRelease := Some(8)
ThisBuild / githubWorkflowOSes := Seq("ubuntu-20.04", "ubuntu-22.04")

ThisBuild / githubWorkflowBuildPreamble +=
  WorkflowStep.Run(
    List("sudo apt-get update", "sudo apt-get install libcurl4-openssl-dev"),
    name = Some("Install libcurl"),
  )
ThisBuild / githubWorkflowBuildPostamble ~= {
  _.filterNot(_.name.contains("Check unused compile dependencies"))
}

val http4sVersion = "0.23.14-101-02562a0-SNAPSHOT"
val munitCEVersion = "2.0-4e051ab-SNAPSHOT"
ThisBuild / resolvers ++= Resolver.sonatypeOssRepos("snapshots")

lazy val root = project.in(file(".")).enablePlugins(NoPublishPlugin).aggregate(curl, example)

lazy val curl = project
  .in(file("curl"))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "http4s-curl",
    libraryDependencies ++= Seq(
      "com.armanbilge" %%% "http4s-client" % http4sVersion,
      "com.armanbilge" %%% "munit-cats-effect" % munitCEVersion % Test,
    ),
  )

lazy val example = project
  .in(file("example"))
  .enablePlugins(ScalaNativePlugin, NoPublishPlugin)
  .dependsOn(curl)
  .settings(
    libraryDependencies ++= Seq(
      "com.armanbilge" %%% "http4s-circe" % http4sVersion
    )
  )
