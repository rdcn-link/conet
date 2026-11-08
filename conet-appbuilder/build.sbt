organization := "io.github.rdcn-link"
name := "conet-appbuilder"
version := "0.1.0-SNAPSHOT"
scalaVersion := "2.13.16"
libraryDependencies ++= Seq(
 "io.github.rdcn-link" %% "dacp-client-scala" % "0.1.0",
 "io.github.rdcn-link" %% "xstream-execution-api" % "0.1.0-SNAPSHOT",
 "org.locationtech.jts" % "jts-core" % "1.20.0",
 "org.scalatest" %% "scalatest" % "3.2.19" % Test)
Test / fork := true
Test / javaOptions ++= Seq("--add-opens=java.base/java.nio=ALL-UNNAMED", "-Dio.netty.tryReflectionSetAccessible=true")
