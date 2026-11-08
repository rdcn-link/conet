organization := "io.github.rdcn-link"
name := "conet"
version := "0.1.0-SNAPSHOT"
scalaVersion := "2.13.16"
enablePlugins(JavaAppPackaging)
Compile / mainClass := Some("conet.Main")
libraryDependencies ++= Seq(
  "org.tomlj" % "tomlj" % "1.1.1",
  "io.github.rdcn-link" %% "dacp-client-scala" % "0.1.0",
  "io.github.rdcn-link" %% "xstream-execution-api" % "0.1.0-SNAPSHOT",
  "org.locationtech.jts" % "jts-core" % "1.20.0",
  "org.scalatest" %% "scalatest" % "3.2.19" % Test,
  "org.slf4j" % "slf4j-simple" % "2.0.16")
Test / fork := true
Test / javaOptions ++= Seq("--add-opens=java.base/java.nio=ALL-UNNAMED", "-Dio.netty.tryReflectionSetAccessible=true")
Universal / javaOptions ++= Seq("-J--add-opens=java.base/java.nio=ALL-UNNAMED", "-J-Dio.netty.tryReflectionSetAccessible=true")

lazy val appbuilder = project.in(file("conet-appbuilder"))
lazy val hosting = project.in(file("conet-hosting"))
lazy val tabular = project.in(file("conet-tabular")).dependsOn(appbuilder)
dependsOn(appbuilder,hosting,tabular)

libraryDependencies += "io.github.rdcn-link" %% "xstream-dacp-protocol" % "0.1.0-SNAPSHOT"
