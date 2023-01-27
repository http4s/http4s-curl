import org.typelevel.sbt.gha.WorkflowStep.Use
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))
ThisBuild / githubWorkflowOSes :=
  Seq("ubuntu-latest", "ubuntu-20.04", "ubuntu-22.04", "macos-11", "macos-12", "windows-2022")
ThisBuild / githubWorkflowBuildMatrixExclusions ++= Seq(
  MatrixExclude(Map("scala" -> Versions.scala3, "os" -> "windows-2022")), // dottydoc bug
  MatrixExclude(Map("project" -> "rootJVM")), // no need to run
)

ThisBuild / githubWorkflowBuildMatrixInclusions ++= Seq(
  MatrixInclude(Map("os" -> "ubuntu-latest"), Map("experimental" -> "yes"))
)

ThisBuild / githubWorkflowBuildPostamble ~= {
  _.filterNot(_.name.contains("Check unused compile dependencies"))
}

ThisBuild / githubWorkflowGeneratedCI ~= {
  _.map { job =>
    if (job.id == "build")
      job.copy(env = job.env.updated("EXPERIMENTAL", s"$${{ matrix.experimental }}"))
    else job
  }
}

val libcurlVersion = "7.87.0"
ThisBuild / githubWorkflowJobSetup ++= Seq(
  WorkflowStep.Run(
    List("sudo apt-get update", "sudo apt-get install libcurl4-openssl-dev"),
    name = Some("Install libcurl (ubuntu)"),
    cond = Some("startsWith(matrix.os, 'ubuntu') && matrix.experimental != 'yes'"),
  ),
  WorkflowStep.Run(
    List(
      "sudo apt-get update",
      "sudo apt-get purge curl",
      "sudo apt-get install libssl-dev autoconf libtool make wget unzip",
      "cd /usr/local/src",
      s"sudo wget https://curl.se/download/curl-$libcurlVersion.zip",
      s"sudo unzip curl-$libcurlVersion.zip",
      s"cd curl-$libcurlVersion",
      "sudo ./configure --with-openssl --enable-websockets",
      "sudo make",
      "sudo make install",
      "curl-config --version",
      "curl-config --protocols",
      """echo "LD_LIBRARY_PATH=/usr/local/lib/" >> $GITHUB_ENV""",
    ),
    name = Some("Build libcurl from source (ubuntu)"),
    cond = Some("startsWith(matrix.os, 'ubuntu') && matrix.experimental == 'yes'"),
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
)

ThisBuild / githubWorkflowBuild ~= { steps =>
  steps.map {
    case step: WorkflowStep.Sbt if step.name == Some("Test") =>
      step.copy(commands = List("integrate"))
    case other => other
  }
}
