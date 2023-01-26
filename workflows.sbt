import org.typelevel.sbt.gha.WorkflowStep.Use
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))
// ThisBuild / tlJdkRelease := Some(8)
ThisBuild / githubWorkflowOSes :=
  Seq("ubuntu-20.04", "ubuntu-22.04", "macos-11", "macos-12", "windows-2022")
ThisBuild / githubWorkflowBuildMatrixExclusions ++= Seq(
  MatrixExclude(Map("scala" -> Versions.scala3, "os" -> "windows-2022")), // dottydoc bug
  MatrixExclude(Map("project" -> "rootJVM")), // no need to run
)

ThisBuild / githubWorkflowBuildPostamble ~= {
  _.filterNot(_.name.contains("Check unused compile dependencies"))
}

lazy val ifWindows = "startsWith(matrix.os, 'windows')"

ThisBuild / githubWorkflowJobSetup ~= {
  _.map { // Setup on windows only, because we use nix on linux and mac
    case step: Use if step.name.exists(_.matches("(Download|Setup) Java .+")) =>
      val newCond = (ifWindows :: step.cond.toList).map(c => s"($c)").mkString(" && ")
      step.copy(cond = Some(newCond))
    case other => other
  }
}

ThisBuild / githubWorkflowJobSetup ++= Seq(
  WorkflowStep.Use(
    UseRef.Public("cachix", "install-nix-action", "v17"),
    name = Some("Install Nix"),
    cond = Some(s"!startsWith(matrix.os, 'windows')"),
  ),
  WorkflowStep.Run(
    List(
      "vcpkg integrate install",
      "vcpkg install --triplet x64-windows curl",
      """cp "C:\vcpkg\installed\x64-windows\lib\libcurl.lib" "C:\vcpkg\installed\x64-windows\lib\curl.lib"""",
    ),
    name = Some("Install libcurl (windows)"),
    cond = Some(ifWindows),
  ),
  WorkflowStep.Run(
    List(
      """([[ "${{ matrix.os }}" =~ "windows" ]] && { echo 'sbt "$@"'; } || { echo 'nix develop .#${{ matrix.java }} -c sbt "$@"'; }) >> sbt-launcher"""
    ),
    name = Some("Create appropriate sbt launcher"),
  ),
  WorkflowStep.Run(
    List("nix develop .#${{ matrix.java }} -c echo 'Ready!'"),
    name = Some("Build nix"),
    cond = Some(s"!startsWith(matrix.os, 'windows')"),
  ),
)

ThisBuild / githubWorkflowSbtCommand := "bash sbt-launcher"

// Add test server setup and destroy
ThisBuild / githubWorkflowBuildPreamble += WorkflowStep.Run(
  List(
    "bash sbt-launcher testServer/run &> /dev/null &",
    s"echo $$! > server.pid",
  ),
  name = Some("Spawn test server"),
)
ThisBuild / githubWorkflowBuildPostamble += WorkflowStep.Run(
  List("cat server.pid | xargs kill", "rm server.pid"),
  name = Some("kill test server"),
)
