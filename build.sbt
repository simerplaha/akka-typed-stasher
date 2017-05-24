name := "akka-typed-stasher"
version := "0.1.0-SNAPSHOT"
scalaVersion := "2.12.2"

scalacOptions += "-deprecation"
logBuffered in Test := false

val akkaTypedVersion = "2.5.2"
val scalaLoggingVersion = "3.5.0"
val logbackClassic = "1.1.7"

resolvers += "Akka Snapshot Repository" at "http://repo.akka.io/snapshots/"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-typed" % akkaTypedVersion,
  "com.typesafe.akka" %% "akka-typed-testkit" % akkaTypedVersion % "test",
  "org.scalatest" %% "scalatest" % "3.0.1" % "test",
  "org.scalacheck" %% "scalacheck" % "1.13.4" % "test",
  "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion,
  "ch.qos.logback" % "logback-classic" % logbackClassic
)