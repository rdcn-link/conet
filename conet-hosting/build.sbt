organization := "io.github.rdcn-link"
name := "conet-hosting"
version := "0.1.0-SNAPSHOT"
scalaVersion := "2.13.16"
libraryDependencies ++= Seq(
 "io.github.rdcn-link" %% "xstream-data-core" % "0.1.0-SNAPSHOT",
 "io.github.rdcn-link" %% "xstream-data-files" % "0.1.0-SNAPSHOT" % Test,
 "io.github.rdcn-link" %% "xstream-data-formats-basic" % "0.1.0-SNAPSHOT" % Test,
 "org.scalatest" %% "scalatest" % "3.2.19" % Test)
Test / fork := true
Test / javaOptions ++= Seq("--add-opens=java.base/java.nio=ALL-UNNAMED", "-Dio.netty.tryReflectionSetAccessible=true")
