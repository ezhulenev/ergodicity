import sbt._
import sbt.Keys._

object ErgodicityBuild extends Build {

  lazy val buildSettings = Seq(
    organization := "com.scalafi.akkatrading",
    version      := "0.0.1",
    scalaVersion := "2.9.1"
  )

  lazy val ergodicity = Project(
    id = "ergodicity",
    base = file("."),
    aggregate = Seq(backtest, cgate, core, capture, engine, schema)
  ).configs( IntegrationTest )
    .settings(Defaults.itSettings : _*)

  lazy val backtest = Project(
    id = "backtest",
    base = file("backtest"),
    dependencies = Seq(engine, schema),
    settings = Project.defaultSettings ++ repositoriesSetting ++ compilerSettings ++ Seq(libraryDependencies ++= Dependencies.backtest)
  ).configs( IntegrationTest )
    .settings( Defaults.itSettings : _*)

  lazy val capture = Project(
    id = "capture",
    base = file("capture"),
    dependencies = Seq(core, schema),
    settings = Project.defaultSettings ++ repositoriesSetting ++ compilerSettings ++ Seq(libraryDependencies ++= Dependencies.capture)
      
  ).configs( IntegrationTest )
    .settings( Defaults.itSettings : _*)

  lazy val engine = Project(
    id = "engine",
    base = file("engine"),
    dependencies = Seq(core),
    settings = Project.defaultSettings ++ repositoriesSetting ++ compilerSettings ++ Seq(libraryDependencies ++= Dependencies.engine)
  ).configs( IntegrationTest )
    .settings( Defaults.itSettings : _*)

  lazy val core = Project(
    id = "core",
    base = file("core"),
    dependencies = Seq(cgate),
    settings = Project.defaultSettings ++ repositoriesSetting ++ compilerSettings ++ Seq(libraryDependencies ++= Dependencies.core)
  ).configs( IntegrationTest )
    .settings( Defaults.itSettings : _*)

  lazy val cgate = Project(
    id = "cgate",
    base = file("cgate"),
    settings = Project.defaultSettings ++ repositoriesSetting ++ compilerSettings ++ Seq(libraryDependencies ++= Dependencies.cgate) ++ Seq(
      (sourceGenerators in Compile) <+= (sourceManaged in Compile) map {
        case out: File => SchemeTools.generateSchemes(file("cgate").getAbsoluteFile, out)
      })
  ).configs( IntegrationTest )
    .settings( Defaults.itSettings : _*)

  lazy val quant = Project(
    id = "quant",
    base = file("quant"),
    dependencies = Seq(core),
    settings = Project.defaultSettings ++ repositoriesSetting ++ compilerSettings ++ Seq(libraryDependencies ++= Dependencies.quant)
  ).configs( IntegrationTest )
    .settings( Defaults.itSettings : _*)

  lazy val schema = Project(
    id = "schema",
    base = file("schema"),
    dependencies = Seq(core),
    settings = Project.defaultSettings ++ repositoriesSetting ++ compilerSettings ++ Seq(libraryDependencies ++= Dependencies.schema)
  ).configs( IntegrationTest )
    .settings( Defaults.itSettings : _*)


  // -- Settings
  
  override lazy val settings = super.settings ++ buildSettings
  
  lazy val compilerSettings = scala.Seq[sbt.Project.Setting[_]](
    scalacOptions += "-unchecked",
    scalacOptions += "-deprecation"
  )

  lazy val repositoriesSetting = Seq(
    resolvers += "Scala tools releases" at "http://scala-tools.org/repo-releases/",
    resolvers += "Scala tools snapshots" at "http://scala-tools.org/repo-snapshots",
    resolvers += "Sonatype Repository" at "http://oss.sonatype.org/content/groups/public/",
    resolvers += "JBoss repository" at "http://repository.jboss.org/nexus/content/repositories/",
    resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
    resolvers += "Typesafe Snapshots Repository" at "http://repo.typesafe.com/typesafe/snapshots/",
    resolvers += "Typesafe Repository ide-2.9" at "http://repo.typesafe.com/typesafe/simple/ide-2.9/",
    resolvers += "Twitter Repository" at "http://maven.twttr.com/",
    resolvers += "Akka Repository" at "http://akka.io/snapshots/",
    resolvers += "Scalafi Repository" at "http://dl.bintray.com/ezhulenev/releases"
  )

}

object Dependencies {
  import Dependency._

  val backtest = Seq(finagleCore, scalaz, marketDbApi, marketDbIteratee, squeryl, h2Driver, postgresDriver, mockito, scalaSTM) ++
    Seq(Test.akkaTestkit, Test.mockito, Test.scalatest)

  val capture = Seq(sbinary, scalaz, finagleKestrel, marketDbApi, squeryl, h2Driver, postgresDriver, ostrich, scalaIO) ++ Seq(Test.akkaTestkit, Test.mockito, Test.scalatest)

  val engine = Seq(commonsMath) ++ Seq(Test.akkaTestkit, Test.mockito, Test.scalatest)

  val core = Seq(scalaz, jodaTime, jodaConvert, scalaTime) ++ Seq(Test.akkaTestkit, Test.mockito, Test.scalatest)

  val cgate = Seq(scalaz, akka, akkaSlf4j, ostrich, logback) ++ Seq(Test.akkaTestkit, Test.mockito, Test.scalatest)

  val quant = Seq(Test.akkaTestkit, Test.mockito, Test.scalatest)

  val schema = Seq(squeryl, h2Driver, postgresDriver) ++ Seq(Test.akkaTestkit, Test.mockito, Test.scalatest)
}


object Dependency {

  // Versions

  object V {
    val MarketDb     = "0.0.1"

    val Scalatest    = "1.6.1"
    val Mockito      = "1.9.0"
    val Scalaz       = "6.0.4"
    val Logback      = "1.0.3"
    val ScalaSTM     = "0.6"
    val JodaTime     = "2.0"
    val JodaConvert  = "1.2"
    val SBinary      = "0.4.0"
    val ScalaTime    = "0.5"
    val Akka         = "2.0.4"
    val ScalaIO      = "0.4.1"
    val Squeryl      = "0.9.5-2"
    val H2           = "1.3.168"
    val Postgres     = "8.4-701.jdbc4"
    val CommonsMath  = "3.0"

    // Async HBase
    val AsyncHBase              = "1.3.2"
    val StumbleuponAsync        = "1.2.0"
    val Zookeeper               = "3.4.3"

    // Twitter dependencies
    val Finagle      = "5.3.6"
    val Ostrich      = "8.2.3"
  }

  // Compile
  val marketDbApi            = "com.scalafi.marketdb"             %% "marketdb-api"           % V.MarketDb intransitive()
  val marketDbIteratee       = "com.scalafi.marketdb"             %% "marketdb-iteratee"      % V.MarketDb intransitive()

  val logback                = "ch.qos.logback"                    % "logback-classic"        % V.Logback
  val scalaz                 = "org.scalaz"                       %% "scalaz-core"            % V.Scalaz
  val scalaSTM               = "org.scala-stm"                    %% "scala-stm"              % V.ScalaSTM
  val jodaTime               = "joda-time"                         % "joda-time"              % V.JodaTime
  val jodaConvert            = "org.joda"                          % "joda-convert"           % V.JodaConvert
  val finagleCore            = "com.twitter"                       % "finagle-core"           % V.Finagle
  val finagleKestrel         = "com.twitter"                       % "finagle-kestrel"        % V.Finagle
  val ostrich                = "com.twitter"                       % "ostrich"                % V.Ostrich
  val sbinary                = "org.scala-tools.sbinary"          %% "sbinary"                % V.SBinary
  val scalaTime              = "org.scala-tools.time"             %% "time"                   % V.ScalaTime intransitive()
  val akka                   = "com.typesafe.akka"                 % "akka-actor"             % V.Akka
  val akkaSlf4j              = "com.typesafe.akka"                 % "akka-slf4j"             % V.Akka
  val scalaIO                = "com.github.scala-incubator.io"    %% "scala-io-core"          % V.ScalaIO
  val squeryl                = "org.squeryl"                      %% "squeryl"                % V.Squeryl
  val h2Driver               = "com.h2database"                    % "h2"                     % V.H2
  val postgresDriver         = "postgresql"                        % "postgresql"             % V.Postgres
  val commonsMath            = "org.apache.commons"                % "commons-math3"          % V.CommonsMath
  val mockito                = "org.mockito"                       % "mockito-all"            % V.Mockito


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
    val akkaTestkit    = "com.typesafe.akka"           % "akka-testkit"                  % V.Akka         % "it, test"
  }
}
