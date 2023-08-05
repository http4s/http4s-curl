import Versions._

ThisBuild / tlBaseVersion := "0.2"

ThisBuild / developers := List(
  tlGitHubDev("armanbilge", "Arman Bilge")
)
ThisBuild / startYear := Some(2022)

ThisBuild / crossScalaVersions := Seq(scala3, scala213)

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

  platformOptions
}

ThisBuild / envVars ++= {
  if (sys.props.get("os.name").exists(_.toLowerCase().contains("windows")))
    Map(
      "PATH" -> s"${sys.props.getOrElse("PATH", "")};${vcpkgBaseDir}/installed/x64-windows/bin/"
    )
  else Map.empty[String, String]
}

def when(pred: => Boolean)(refs: CompositeProject*) = if (pred) refs else Nil

lazy val modules = List(
  curl,
  example,
  testServer,
  testCommon,
  httpTestSuite,
) ++ when(sys.env.get("EXPERIMENTAL").contains("yes"))(websocketTestSuite)

lazy val root =
  tlCrossRootProject
    .enablePlugins(NoPublishPlugin)
    .aggregate(modules: _*)

lazy val curl = project
  .in(file("curl"))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "http4s-curl",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-effect" % catsEffectVersion,
      "org.http4s" %%% "http4s-client" % http4sVersion,
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
      "ch.qos.logback" % "logback-classic" % "1.4.9",
    )
  )

//NOTE
//It's important to keep tests separated from source code,
//so that we can prevent linking a category of tests
//in platforms that don't support those features
//
lazy val testCommon = project
  .in(file("tests/common"))
  .enablePlugins(ScalaNativePlugin, NoPublishPlugin)
  .dependsOn(curl)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "munit-cats-effect" % munitCEVersion
    )
  )

lazy val httpTestSuite = project
  .in(file("tests/http"))
  .enablePlugins(ScalaNativePlugin, NoPublishPlugin)
  .dependsOn(testCommon)

lazy val websocketTestSuite = project
  .in(file("tests/websocket"))
  .enablePlugins(ScalaNativePlugin, NoPublishPlugin)
  .dependsOn(testCommon)

lazy val startTestServer = taskKey[Unit]("starts test server if not running")
lazy val stopTestServer = taskKey[Unit]("stops test server if running")

ThisBuild / startTestServer := {
  (testServer / Compile / compile).value
  val cp = (testServer / Compile / fullClasspath).value.files
  TestServer.setClassPath(cp)
  TestServer.setLog(streams.value.log)
  TestServer.start()
}

ThisBuild / stopTestServer := {
  TestServer.stop()
}

addCommandAlias("integrate", "startTestServer; test")
