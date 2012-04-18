import sbt._
import sbt.Keys._

object EngineBuild extends Build {

  lazy val buildSettings = Seq(
    organization := "com.ergodicity.engine",
    version      := "0.1-SNAPSHOT",
    scalaVersion := "2.9.1"
  )

  lazy val engine = Project(
    id = "engine",
    base = file("."),
    aggregate = Seq(capture, core, plaza2)
  ).configs( IntegrationTest )
    .settings( Defaults.itSettings : _*)

  lazy val capture = Project(
    id = "capture",
    base = file("capture"),
    dependencies = Seq(core),
    settings = Project.defaultSettings ++ repositoriesSetting ++ Seq(libraryDependencies ++= Dependencies.capture)
  ).configs( IntegrationTest )
    .settings( Defaults.itSettings : _*)


  lazy val core = Project(
    id = "core",
    base = file("core"),
    dependencies = Seq(plaza2),
    settings = Project.defaultSettings ++ repositoriesSetting ++ Seq(libraryDependencies ++= Dependencies.core)
  ).configs( IntegrationTest )
    .settings( Defaults.itSettings : _*)

  lazy val plaza2 = Project(
    id = "plaza2",
    base = file("plaza2"),
    settings = Project.defaultSettings ++ repositoriesSetting ++ Seq(libraryDependencies ++= Dependencies.plaza2)
  ).configs( IntegrationTest )
    .settings( Defaults.itSettings : _*)

  // -- Settings
  
  override lazy val settings = super.settings ++ buildSettings

  lazy val repositoriesSetting = Seq(
    resolvers += "Sonatype Repository" at "http://oss.sonatype.org/content/groups/public/",
    resolvers += "JBoss repository" at "http://repository.jboss.org/nexus/content/repositories/",
    resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
    resolvers += "Typesafe Snapshots Repository" at "http://repo.typesafe.com/typesafe/snapshots/",
    resolvers += "Typesafe Repository ide-2.9" at "http://repo.typesafe.com/typesafe/simple/ide-2.9/",
    resolvers += "Twitter Repository" at "http://maven.twttr.com/",
    resolvers += "Akka Repository" at "http://akka.io/snapshots/"
  )
}

object Dependencies {
  import Dependency._

  val capture = Seq(ostrich, Test.akkaTestkit, Test.mockito, Test.scalatest, Test.scalacheck)

  val core = Seq(Test.akkaTestkit, Test.mockito, Test.scalatest, Test.scalacheck)

  val plaza2 = Seq(plaza2Connectivity, akka, scalaTime, slf4jApi, logback, jodaTime, jodaConvert) ++
    Seq(Test.akkaTestkit, Test.mockito, Test.scalatest, Test.scalacheck)
}


object Dependency {

  // Versions

  object V {
    val Plaza2       = "0.1-SNAPSHOT"

    val Scalatest    = "1.6.1"
    val Slf4j        = "1.6.4"
    val Mockito      = "1.8.1"
    val Scalacheck   = "1.9"
    val Scalaz       = "6.0.4"
    val Logback      = "1.0.0"
    val ScalaSTM     = "0.4"
    val JodaTime     = "2.0"
    val JodaConvert  = "1.2"
    val Finagle      = "1.11.1"
    val SBinary      = "0.4.0"
    val ScalaTime    = "0.5"
    val Ostrich      = "4.10.6"
    val Akka         = "2.0"
  }

  // Compile
  val plaza2Connectivity     = "com.ergodicity.connectivity"      %% "plaza2"                 % V.Plaza2

  val slf4jApi               = "org.slf4j"                         % "slf4j-api"              % V.Slf4j
  val logback                = "ch.qos.logback"                    % "logback-classic"        % V.Logback
  val scalaz                 = "org.scalaz"                       %% "scalaz-core"            % V.Scalaz
  val scalaSTM               = "org.scala-tools"                  %% "scala-stm"              % V.ScalaSTM
  val jodaTime               = "joda-time"                         % "joda-time"              % V.JodaTime
  val jodaConvert            = "org.joda"                          % "joda-convert"           % V.JodaConvert
  val finagleCore            = "com.twitter"                      %% "finagle-core"           % V.Finagle
  val ostrich                = "com.twitter"                      %% "ostrich"                % V.Ostrich
  val sbinary                = "org.scala-tools.sbinary"          %% "sbinary"                % V.SBinary
  val scalaTime              = "org.scala-tools.time"             %% "time"                   % V.ScalaTime
  val akka                   = "com.typesafe.akka"                 % "akka-actor"             % V.Akka

  // Provided

  object Provided {

  }

  // Runtime

  object Runtime {

  }

  // Test

  object Test {
    val mockito        = "org.mockito"                 % "mockito-all"                   % V.Mockito      % "test"
    val scalatest      = "org.scalatest"              %% "scalatest"                     % V.Scalatest    % "it,test"
    val scalacheck     = "org.scala-tools.testing"    %% "scalacheck"                    % V.Scalacheck   % "test"
    val akkaTestkit    = "com.typesafe.akka"           % "akka-testkit"                  % V.Akka         % "it, test"
  }
}