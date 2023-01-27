import org.typelevel.sbt.gha.WorkflowStep.Use
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))
// ThisBuild / tlJdkRelease := Some(8)
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

// lazy val ifWindows = "startsWith(matrix.os, 'windows')"

// ThisBuild / githubWorkflowJobSetup ~= {
//   _.map { // Setup on windows only, because we use nix on linux and mac
//     case step: Use if step.name.exists(_.matches("(Download|Setup) Java .+")) =>
//       val newCond = (ifWindows :: step.cond.toList).map(c => s"($c)").mkString(" && ")
//       step.copy(cond = Some(newCond))
//     case other => other
//   }
// }
//

ThisBuild / githubWorkflowGeneratedCI ~= {
  _.map { job =>
    if (job.id == "build")
      job.copy(env = job.env.updated("EXPERIMENTAL", s"$${{ matrix.experimental }}"))
    else job
  }
}

ThisBuild / githubWorkflowJobSetup ++= Seq(
  // WorkflowStep.Use(
  //   UseRef.Public("cachix", "install-nix-action", "v17"),
  //   name = Some("Install Nix"),
  //   cond = Some(s"!startsWith(matrix.os, 'windows')"),
  // ),
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
      "sudo wget https://curl.se/download/curl-7.87.0.zip",
      "sudo unzip curl-7.87.0.zip",
      "cd curl-7.87.0",
      "sudo ./configure --with-openssl --enable-websockets",
      "sudo make",
      "sudo make install",
      "curl-config --version",
      "curl-config --protocols",
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
  // WorkflowStep.Run(
  //   List(
  //     """([[ "${{ matrix.os }}" =~ "windows" ]] && { echo 'sbt "$@"'; } || { echo 'nix develop .#${{ matrix.java }} -c sbt "$@"'; }) >> sbt-launcher"""
  //   ),
  //   name = Some("Create appropriate sbt launcher"),
  // ),
  // WorkflowStep.Run(
  //   List(
  //     "nix develop .#${{ matrix.java }} -c curl -V",
  //   ),
  //   name = Some("Build nix"),
  //   cond = Some(s"!startsWith(matrix.os, 'windows')"),
  // ),
)

ThisBuild / githubWorkflowBuild ~= { steps =>
  steps.map {
    case step: WorkflowStep.Sbt if step.name == Some("Test") =>
      step.copy(commands = List("integrate"))
    case other => other
  }
}
