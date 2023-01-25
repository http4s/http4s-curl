ThisBuild / tlBaseVersion := "0.1"

ThisBuild / developers := List(
  tlGitHubDev("armanbilge", "Arman Bilge")
)
ThisBuild / startYear := Some(2022)

val scala3 = "3.2.1"
ThisBuild / crossScalaVersions := Seq(scala3, "2.13.10")
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))
// ThisBuild / tlJdkRelease := Some(8)
ThisBuild / githubWorkflowOSes :=
  Seq("ubuntu-20.04", "ubuntu-22.04", "macos-11", "macos-12", "windows-2022")
ThisBuild / githubWorkflowBuildMatrixExclusions +=
  MatrixExclude(Map("scala" -> scala3, "os" -> "windows-2022")) // dottydoc bug

lazy val setupTestServer =
  WorkflowStep.Run(
    List(
      "sbt testServer/run &> /dev/null &",
      s"echo $$! > server.pid",
    ),
    name = Some("Spawn test server"),
  )
lazy val destroyTestServer =
  WorkflowStep.Run(
    List("cat server.pid | xargs kill", "rm server.pid"),
    name = Some("kill test server"),
  )

ThisBuild / githubWorkflowBuildPreamble ++= Seq(
  WorkflowStep.Run(
    List("sudo apt-get update", "sudo apt-get install libcurl4-openssl-dev"),
    name = Some("Install libcurl (ubuntu)"),
    cond = Some("startsWith(matrix.os, 'ubuntu')"),
  ),
  WorkflowStep.Run(
    List(
      "vcpkg integrate install",
      "vcpkg install --triplet x64-windows curl",
      """cp "C:\vcpkg\installed\x64-windows\lib\libcurl.lib" "C:\vcpkg\installed\x64-windows\lib\curl.lib"""",
    ),
    name = Some("Install libcurl (windows)"),
    cond = Some("startsWith(matrix.os, 'windows')"),
  ),
  setupTestServer,
)
ThisBuild / githubWorkflowBuildPostamble ~= {
  _.filterNot(_.name.contains("Check unused compile dependencies"))
}

ThisBuild / githubWorkflowBuildPostamble += destroyTestServer

val catsEffectVersion = "3.4.5"
val http4sVersion = "0.23.18"
val munitCEVersion = "2.0.0-M3"

val vcpkgBaseDir = "C:/vcpkg/"

ThisBuild / nativeConfig ~= { c =>
  val osNameOpt = sys.props.get("os.name")
  val isMacOs = osNameOpt.exists(_.toLowerCase().contains("mac"))
  val isWindows = osNameOpt.exists(_.toLowerCase().contains("windows"))
  val platformOptions = if (isMacOs) { // brew-installed curl
    c.withLinkingOptions(c.linkingOptions :+ "-L/usr/local/opt/curl/lib")
  } else if (isWindows) { // vcpkg-installed curl
    c.withCompileOptions(c.compileOptions :+ s"-I${vcpkgBaseDir}/installed/x64-windows/include/")
      .withLinkingOptions(c.linkingOptions :+ s"-L${vcpkgBaseDir}/installed/x64-windows/lib/")
  } else c

  platformOptions.withLinkingOptions(platformOptions.linkingOptions :+ "-lcurl")
}

ThisBuild / envVars ++= {
  if (sys.props.get("os.name").exists(_.toLowerCase().contains("windows")))
    Map(
      "PATH" -> s"${sys.props.getOrElse("PATH", "")};${vcpkgBaseDir}/installed/x64-windows/bin/"
    )
  else Map.empty[String, String]
}

lazy val root =
  project.in(file(".")).enablePlugins(NoPublishPlugin).aggregate(curl, example, testServer)

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

lazy val testServer = project
  .in(file("test-server"))
  .enablePlugins(NoPublishPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "ch.qos.logback" % "logback-classic" % "1.2.6",
    ),
    fork := true,
  )
