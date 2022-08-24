addSbtPlugin("org.http4s" % "sbt-http4s-org" % "0.14.4")
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.4.5")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.2.0")

// TODO temporary
addSbtPlugin("org.typelevel" % "sbt-typelevel-github-actions" % "0.4.13-10-cedbed3-SNAPSHOT")
resolvers ++= Resolver.sonatypeOssRepos("snapshots")
